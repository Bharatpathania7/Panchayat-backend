package com.panchayat.backend.newbie;

import com.panchayat.backend.admin.media.MediaUrls;
import com.panchayat.backend.common.PublicIds;
import com.panchayat.backend.rooms.service.RoomStateService;
import com.panchayat.backend.voice.entity.VoiceRoom;
import com.panchayat.backend.voice.entity.VoiceRoomType;
import com.panchayat.backend.voice.repository.VoiceRoomRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Newbie Welcome Rooms. A welcome room is an ordinary voice room (so seats, chat, gifts and LiveKit work as they
 * always do) that is flagged as a newbie room and hosted by someone from the admin's host list. This service owns
 * the settings, who counts as a newbie, room creation and assignment, discovery, the extra seats, the Welcome
 * Newbie gift rules, host reassignment and cleanup, and the numbers shown in the admin panel.
 */
@Service
public class NewbieService {

    private static final Logger log = LoggerFactory.getLogger(NewbieService.class);
    /** One lock for creating and assigning rooms, so simultaneous sign-ups can't create two rooms or break the room limit. */
    private static final long LOCK = 771_000_001L;

    private final JdbcTemplate jdbc;
    private final VoiceRoomRepository rooms;
    private final RoomStateService roomState;

    public NewbieService(JdbcTemplate jdbc, VoiceRoomRepository rooms, @Lazy RoomStateService roomState) {
        this.jdbc = jdbc;
        this.rooms = rooms;
        this.roomState = roomState;
    }

    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }

    private static ResponseStatusException conflict(String m) {
        return new ResponseStatusException(HttpStatus.CONFLICT, m);
    }

    private static ResponseStatusException forbidden(String m) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, m);
    }

    // ------------------------------------------------------------------ settings

    public record Config(boolean autoCreate, int eligibilityDays, int maxRooms, int roomCapacity, int roomLifetimeHours, boolean extraSeatsAllowed,
                         int hostGraceMinutes, boolean welcomeGiftEnabled, String welcomeGiftId, String welcomeTitle, String welcomeMessage,
                         String roomDescription, int priority) {
    }

    private volatile Config cached;
    private volatile long cachedAt;

    /** The admin's settings, read from the database at most every few seconds. */
    public Config config() {
        Config c = cached;
        if (c != null && System.currentTimeMillis() - cachedAt < 5_000) return c;
        Config fresh = jdbc.queryForObject("select auto_create, eligibility_days, max_rooms, room_capacity, room_lifetime_hours, extra_seats_allowed, host_grace_minutes,"
                        + " welcome_gift_enabled, welcome_gift_id, welcome_title, welcome_message, room_description, priority from newbie_room_config where id = 1",
                (rs, i) -> new Config(rs.getBoolean(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5), rs.getBoolean(6), rs.getInt(7), rs.getBoolean(8),
                        rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12), rs.getInt(13)));
        cached = fresh;
        cachedAt = System.currentTimeMillis();
        return fresh;
    }

    public Map<String, Object> configMap() {
        Config c = config();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("autoCreate", c.autoCreate());
        m.put("eligibilityDays", c.eligibilityDays());
        m.put("maxRooms", c.maxRooms());
        m.put("roomCapacity", c.roomCapacity());
        m.put("roomLifetimeHours", c.roomLifetimeHours());
        m.put("extraSeatsAllowed", c.extraSeatsAllowed());
        m.put("hostGraceMinutes", c.hostGraceMinutes());
        m.put("welcomeGiftEnabled", c.welcomeGiftEnabled());
        m.put("welcomeGiftId", c.welcomeGiftId());
        m.put("welcomeTitle", c.welcomeTitle());
        m.put("welcomeMessage", c.welcomeMessage());
        m.put("roomDescription", c.roomDescription());
        m.put("priority", c.priority());
        return m;
    }

    private static int intIn(Object v, int def, int min, int max, String label) {
        if (v == null) return def;
        int n;
        try {
            n = v instanceof Number x ? x.intValue() : Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw bad(label + " must be a number.");
        }
        if (n < min || n > max) throw bad(label + " must be between " + min + " and " + max + ".");
        return n;
    }

    private static boolean boolIn(Object v, boolean def) {
        return v == null ? def : v instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(v));
    }

    private static String textIn(Object v, String def, int max, String label) {
        if (v == null) return def;
        String t = String.valueOf(v).strip();
        if (t.isEmpty()) throw bad(label + " can't be empty.");
        if (t.length() > max) throw bad(label + " can be up to " + max + " characters.");
        return t;
    }

    @Transactional
    public Map<String, Object> saveConfig(Map<String, Object> in) {
        Config c = config();
        String giftId = textIn(in.get("welcomeGiftId"), c.welcomeGiftId(), 64, "The welcome gift");
        if (jdbc.queryForObject("select count(*) from gifts where id = ?", Long.class, giftId) == 0) throw bad("That gift doesn't exist. Create it in Gifts first.");
        jdbc.update("update newbie_room_config set auto_create = ?, eligibility_days = ?, max_rooms = ?, room_capacity = ?, room_lifetime_hours = ?, extra_seats_allowed = ?,"
                        + " host_grace_minutes = ?, welcome_gift_enabled = ?, welcome_gift_id = ?, welcome_title = ?, welcome_message = ?, room_description = ?, priority = ? where id = 1",
                boolIn(in.get("autoCreate"), c.autoCreate()),
                intIn(in.get("eligibilityDays"), c.eligibilityDays(), 1, 365, "The Newbie period (days)"),
                intIn(in.get("maxRooms"), c.maxRooms(), 1, 100, "The maximum number of rooms"),
                intIn(in.get("roomCapacity"), c.roomCapacity(), 16, 50, "The room capacity"),
                intIn(in.get("roomLifetimeHours"), c.roomLifetimeHours(), 1, 720, "The room lifetime (hours)"),
                boolIn(in.get("extraSeatsAllowed"), c.extraSeatsAllowed()),
                intIn(in.get("hostGraceMinutes"), c.hostGraceMinutes(), 1, 120, "The host grace time (minutes)"),
                boolIn(in.get("welcomeGiftEnabled"), c.welcomeGiftEnabled()),
                giftId,
                textIn(in.get("welcomeTitle"), c.welcomeTitle(), 80, "The welcome title"),
                textIn(in.get("welcomeMessage"), c.welcomeMessage(), 600, "The welcome message"),
                textIn(in.get("roomDescription"), c.roomDescription(), 300, "The room description"),
                intIn(in.get("priority"), c.priority(), 0, 1000, "The priority"));
        cached = null;
        return configMap();
    }

    // ------------------------------------------------------------------ who is a newbie

    /** A newbie is someone whose account is younger than the admin's Newbie period. */
    public boolean isNewbie(Long userId) {
        if (userId == null) return false;
        Boolean b = jdbc.queryForObject("select exists (select 1 from users where id = ? and created_at > now() - make_interval(days => ?))", Boolean.class, userId, config().eligibilityDays());
        return Boolean.TRUE.equals(b);
    }

    /** Which of these people are newbies (for the badge). */
    public Set<Long> newbies(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return Set.of();
        List<Long> list = ids.stream().distinct().limit(100).toList();
        String marks = String.join(",", list.stream().map(x -> "?").toList());
        List<Object> args = new ArrayList<>(list);
        args.add(config().eligibilityDays());
        return new HashSet<>(jdbc.queryForList("select id from users where id in (" + marks + ") and created_at > now() - make_interval(days => ?)", Long.class, args.toArray()));
    }

    // ------------------------------------------------------------------ sign-up

    /** Called after a new account is created. It never fails the sign-up. */
    public void onRegistered(Long userId) {
        try {
            jdbc.update("insert into newbie_user_status (user_id) values (?) on conflict do nothing", userId);
            assign(userId);
        } catch (Exception e) {
            log.warn("Newbie welcome for user {} didn't finish: {}", userId, e.getMessage());
        }
    }

    /** Finds the welcome room that suits a newbie best (creating one if the admin allows it) and remembers it. */
    @Transactional
    public Long assign(Long userId) {
        jdbc.query("select pg_advisory_xact_lock(?)", rs -> null, LOCK);
        Long room = bestRoom();
        if (room == null && config().autoCreate()) {
            Long host = freeHost();
            if (host != null && activeCount() < config().maxRooms()) room = createRoomLocked(host, "auto");
        }
        if (room != null) {
            jdbc.update("update newbie_user_status set room_id = ?, assigned_at = now() where user_id = ?", room, userId);
            event(room, userId, "ASSIGNED", null);
        }
        return room;
    }

    private Long bestRoom() {
        List<Long> l = jdbc.queryForList("select nr.room_id from newbie_rooms nr join voice_rooms vr on vr.id = nr.room_id"
                + " where nr.status = 'ACTIVE' and vr.status = 'ACTIVE' and (nr.expires_at is null or nr.expires_at > now())"
                + " and (select count(*) from voice_room_participants p where p.room_id = nr.room_id and p.status = 'JOINED') < vr.max_participants"
                + " order by exists (select 1 from voice_room_participants p where p.room_id = nr.room_id and p.user_id = nr.host_id and p.status = 'JOINED') desc,"
                + " nr.priority, (select count(*) from voice_room_participants p where p.room_id = nr.room_id and p.status = 'JOINED') limit 1", Long.class);
        return l.isEmpty() ? null : l.get(0);
    }

    private long activeCount() {
        Long n = jdbc.queryForObject("select count(*) from newbie_rooms nr join voice_rooms vr on vr.id = nr.room_id where nr.status = 'ACTIVE' and vr.status = 'ACTIVE'", Long.class);
        return n == null ? 0 : n;
    }

    /** A host from the list who isn't running another active welcome room. */
    private Long freeHost() {
        List<Long> l = jdbc.queryForList("select h.user_id from newbie_room_hosts h where h.active"
                + " and not exists (select 1 from newbie_rooms r where r.host_id = h.user_id and r.status = 'ACTIVE')"
                + " order by exists (select 1 from voice_room_participants p join voice_rooms v on v.id = p.room_id where p.user_id = h.user_id and p.status = 'JOINED' and v.status = 'ACTIVE') desc,"
                + " (select count(*) from newbie_rooms r where r.host_id = h.user_id), h.user_id limit 1", Long.class);
        return l.isEmpty() ? null : l.get(0);
    }

    // ------------------------------------------------------------------ rooms

    /** Creates a welcome room hosted by `hostId`. The number and name come from the backend and are unique in the database. */
    @Transactional
    public Map<String, Object> createRoom(Long hostId, String by) {
        jdbc.query("select pg_advisory_xact_lock(?)", rs -> null, LOCK);
        if (hostId == null) throw bad("Pick a host.");
        if (jdbc.queryForObject("select count(*) from users where id = ?", Long.class, hostId) == 0) throw bad("That user doesn't exist.");
        if (jdbc.queryForObject("select count(*) from newbie_rooms where host_id = ? and status = 'ACTIVE'", Long.class, hostId) > 0) throw conflict("That host already has an active welcome room.");
        if (activeCount() >= config().maxRooms()) throw conflict("You have reached the maximum number of welcome rooms (" + config().maxRooms() + ").");
        jdbc.update("insert into newbie_room_hosts (user_id, added_by) values (?, ?) on conflict (user_id) do update set active = true", hostId, by);
        Long roomId = createRoomLocked(hostId, by);
        return one(roomId);
    }

    private Long createRoomLocked(Long hostId, String by) {
        Config c = config();
        long number = jdbc.queryForObject("select nextval('newbie_room_number_seq')", Long.class);
        String name = "Newbie Welcome #" + number;
        VoiceRoom room = new VoiceRoom();
        room.setOwnerId(hostId);
        room.setName(name);
        room.setDescription(c.roomDescription());
        room.setRoomType(VoiceRoomType.VOICE);
        room.setPrivate(false);
        room.setMaxParticipants(c.roomCapacity());
        room.setCategory("Newbie");
        room.setNewbie(true);
        VoiceRoom saved = rooms.saveAndFlush(room);
        jdbc.update("insert into newbie_rooms (room_id, room_number, name, host_id, priority, expires_at, created_by) values (?, ?, ?, ?, ?, now() + make_interval(hours => ?), ?)",
                saved.getId(), (int) number, name, hostId, c.priority(), c.roomLifetimeHours(), by);
        event(saved.getId(), hostId, "ROOM_CREATED", name);
        return saved.getId();
    }

    private void event(Long roomId, Long userId, String kind, String detail) {
        try {
            jdbc.update("insert into newbie_room_events (room_id, user_id, kind, detail) values (?, ?, ?, ?)", roomId, userId, kind, detail);
        } catch (Exception e) {
            log.debug("Newbie event not saved: {}", e.getMessage());
        }
    }

    private static final String ROOM_SELECT = "select nr.id, nr.room_id, nr.room_number, nr.name, nr.status, nr.priority, nr.expires_at, nr.created_at, nr.created_by, nr.host_id, nr.host_absent_since,"
            + " vr.max_participants, vr.extra_seats, vr.status as room_status,"
            + " u.full_name as host_name, u.avatar_url as host_avatar, u.public_id as host_public_id,"
            + " (select count(*) from voice_room_participants p where p.room_id = nr.room_id and p.status = 'JOINED') as joined,"
            + " (select count(*) from voice_room_participants p join users x on x.id = p.user_id where p.room_id = nr.room_id and p.status = 'JOINED' and x.created_at > now() - make_interval(days => ?)) as newbies_in,"
            + " exists (select 1 from voice_room_participants p where p.room_id = nr.room_id and p.user_id = nr.host_id and p.status = 'JOINED') as host_present,"
            + " (select count(*) from newbie_room_participants q where q.room_id = nr.room_id) as visitors,"
            + " (select count(*) from newbie_welcome_gifts g where g.room_id = nr.room_id) as gifts"
            + " from newbie_rooms nr join voice_rooms vr on vr.id = nr.room_id left join users u on u.id = nr.host_id";

    private Map<String, Object> row(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        long roomId = rs.getLong("room_id");
        m.put("id", rs.getLong("id"));
        m.put("roomId", roomId);
        m.put("roomCode", PublicIds.room(roomId));
        m.put("number", rs.getInt("room_number"));
        m.put("name", rs.getString("name"));
        m.put("status", rs.getString("status"));
        m.put("roomStatus", rs.getString("room_status"));
        m.put("priority", rs.getInt("priority"));
        m.put("expiresAt", rs.getTimestamp("expires_at") == null ? null : rs.getTimestamp("expires_at").toInstant().toString());
        m.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
        m.put("createdBy", rs.getString("created_by"));
        Map<String, Object> host = new LinkedHashMap<>();
        host.put("id", rs.getObject("host_id") == null ? null : String.valueOf(rs.getLong("host_id")));
        host.put("name", rs.getString("host_name"));
        host.put("avatarUrl", MediaUrls.link(rs.getString("host_avatar")));
        host.put("publicId", rs.getObject("host_public_id"));
        host.put("online", rs.getBoolean("host_present"));
        m.put("host", host);
        m.put("capacity", rs.getInt("max_participants"));
        m.put("joined", rs.getLong("joined"));
        m.put("seatsFree", Math.max(0, rs.getInt("max_participants") - rs.getLong("joined")));
        m.put("newbiesInside", rs.getLong("newbies_in"));
        m.put("extraSeats", rs.getBoolean("extra_seats"));
        m.put("visitors", rs.getLong("visitors"));
        m.put("welcomeGifts", rs.getLong("gifts"));
        return m;
    }

    private Map<String, Object> one(Long roomId) {
        List<Map<String, Object>> l = jdbc.query(ROOM_SELECT + " where nr.room_id = ?", (rs, i) -> row(rs), config().eligibilityDays(), roomId);
        if (l.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Welcome room not found");
        return l.get(0);
    }

    private final Map<String, Object> discoveryMemo = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The welcome rooms the app shows at the very top: only rooms that are really open, ranked by the server:
     * an online host first, then rooms with seats free, then the admin's priority, then how lively they are.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> discover() {
        Object memo = discoveryMemo.get("rooms");
        Object at = discoveryMemo.get("at");
        if (memo != null && at instanceof Long t && System.currentTimeMillis() - t < 3_000) return (List<Map<String, Object>>) memo;
        List<Map<String, Object>> l = jdbc.query(ROOM_SELECT
                        + " where nr.status = 'ACTIVE' and vr.status = 'ACTIVE' and (nr.expires_at is null or nr.expires_at > now())"
                        + " order by host_present desc, ((select count(*) from voice_room_participants p where p.room_id = nr.room_id and p.status = 'JOINED') < vr.max_participants) desc,"
                        + " nr.priority, joined desc, nr.room_number limit 20",
                (rs, i) -> row(rs), config().eligibilityDays());
        discoveryMemo.put("rooms", l);
        discoveryMemo.put("at", System.currentTimeMillis());
        return l;
    }

    // ------------------------------------------------------------------ the signed-in person

    public Map<String, Object> me(Long userId) {
        Config c = config();
        Map<String, Object> m = new LinkedHashMap<>();
        boolean newbie = isNewbie(userId);
        m.put("newbie", newbie);
        List<Map<String, Object>> st = jdbc.queryForList("select s.room_id, s.welcome_seen_at, u.created_at from users u left join newbie_user_status s on s.user_id = u.id where u.id = ?", userId);
        Map<String, Object> s = st.isEmpty() ? Map.of() : st.get(0);
        java.sql.Timestamp created = (java.sql.Timestamp) s.get("created_at");
        m.put("expiresAt", newbie && created != null ? created.toInstant().plusSeconds(c.eligibilityDays() * 86_400L).toString() : null);
        m.put("welcomeSeen", s.get("welcome_seen_at") != null);
        m.put("roomId", s.get("room_id") == null ? null : String.valueOf(s.get("room_id")));
        m.put("isHost", jdbc.queryForObject("select exists (select 1 from newbie_room_hosts where user_id = ? and active)", Boolean.class, userId));
        m.put("welcomeGiftId", c.welcomeGiftEnabled() ? c.welcomeGiftId() : null);
        m.put("welcomeTitle", c.welcomeTitle());
        m.put("welcomeMessage", c.welcomeMessage());
        m.put("extraSeatsAllowed", c.extraSeatsAllowed());
        m.put("gotWelcomeGift", jdbc.queryForObject("select exists (select 1 from newbie_welcome_gifts where receiver_id = ?)", Boolean.class, userId));
        return m;
    }

    @Transactional
    public void welcomeSeen(Long userId) {
        jdbc.update("insert into newbie_user_status (user_id, welcome_seen_at) values (?, now()) on conflict (user_id) do update set welcome_seen_at = coalesce(newbie_user_status.welcome_seen_at, now())", userId);
    }

    /** Called when someone first enters a welcome room. */
    public void onJoin(Long roomId, Long userId) {
        try {
            boolean newbie = isNewbie(userId);
            jdbc.update("insert into newbie_room_participants (room_id, user_id, newbie) values (?, ?, ?)"
                    + " on conflict (room_id, user_id) do update set joins = newbie_room_participants.joins + 1, last_joined_at = now()", roomId, userId, newbie);
            if (newbie) {
                jdbc.update("insert into newbie_user_status (user_id, first_join_at) values (?, now()) on conflict (user_id) do update set first_join_at = coalesce(newbie_user_status.first_join_at, now())", userId);
                event(roomId, userId, "JOINED", null);
            }
        } catch (Exception e) {
            log.debug("Newbie join not saved: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------ extra seats

    /** The host (or a room admin, or a panel admin) opens or closes the 6 extra seats. People already in the room stay; only seats that no longer exist are emptied. */
    @Transactional
    public Map<String, Object> setExtraSeats(Long roomId, Long actor, boolean enabled, boolean panelAdmin) {
        VoiceRoom room = rooms.findById(roomId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found"));
        if (!room.isNewbie()) throw bad("Extra welcome seats are only for Newbie Welcome Rooms.");
        if (!"ACTIVE".equals(room.getStatus())) throw conflict("This room is closed.");
        if (!panelAdmin) {
            boolean owner = actor != null && actor.equals(room.getOwnerId());
            boolean admin = actor != null && jdbc.queryForObject("select exists (select 1 from voice_room_admins where room_id = ? and user_id = ?)", Boolean.class, roomId, actor);
            if (!owner && !admin) throw forbidden("Only the host or a room admin can change the extra seats.");
        }
        if (enabled && !config().extraSeatsAllowed()) throw forbidden("Extra welcome seats are turned off.");
        if (enabled && room.getMaxParticipants() < RoomStateService.seatCount(room) + RoomStateService.SEATS_EXTRA) throw bad("This room is too small for the extra seats.");
        if (room.isExtraSeats() != enabled) {
            room.setExtraSeats(enabled);
            rooms.saveAndFlush(room);
            event(roomId, actor, enabled ? "EXTRA_SEATS_ON" : "EXTRA_SEATS_OFF", null);
            // Resize the live seats after the change is saved, and tell everyone in the room
            afterCommit(() -> roomState.onModeChanged(roomId));
        }
        return Map.of("roomId", roomId, "extraSeats", enabled);
    }

    private static void afterCommit(Runnable r) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        r.run();
                    } catch (Exception e) {
                        log.warn("Newbie room update failed: {}", e.getMessage());
                    }
                }
            });
        } else {
            r.run();
        }
    }

    // ------------------------------------------------------------------ the Welcome Newbie gift

    private boolean isWelcomeGift(String giftId) {
        return giftId != null && giftId.equals(config().welcomeGiftId());
    }

    /** Throws unless this gift may be sent: only the host of a welcome room, one at a time, to a newbie who hasn't had it, paid from the host's own coins. */
    public void checkWelcomeGift(VoiceRoom room, Long senderId, Long receiverId, String giftId, int count, boolean fromBag) {
        if (!isWelcomeGift(giftId)) return;
        if (!config().welcomeGiftEnabled()) throw forbidden("The Welcome Newbie gift is turned off.");
        if (!room.isNewbie()) throw forbidden("The Welcome Newbie gift can only be sent in a Newbie Welcome Room.");
        if (fromBag) throw bad("The Welcome Newbie gift can't come from a Bag.");
        if (count != 1) throw bad("Send one Welcome Newbie gift at a time.");
        boolean host = senderId.equals(room.getOwnerId())
                || jdbc.queryForObject("select exists (select 1 from newbie_room_hosts where user_id = ? and active)", Boolean.class, senderId);
        if (!host) throw forbidden("Only the host of a welcome room can send this gift.");
        if (senderId.equals(receiverId)) throw bad("Send it to a new member.");
        if (!isNewbie(receiverId)) throw conflict("This gift is only for new members.");
        if (Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from newbie_welcome_gifts where receiver_id = ?)", Boolean.class, receiverId))) {
            throw conflict("They have already received a welcome gift.");
        }
    }

    /** Saves the welcome gift in the same transaction as the gift itself. The unique receiver stops a second one even if two are sent at once. */
    public void recordWelcomeGift(VoiceRoom room, Long senderId, Long receiverId, String giftId, long coins, String requestId) {
        if (!isWelcomeGift(giftId) || !room.isNewbie()) return;
        try {
            jdbc.update("insert into newbie_welcome_gifts (room_id, host_id, receiver_id, gift_id, coins, request_id) values (?, ?, ?, ?, ?, ?)",
                    room.getId(), senderId, receiverId, giftId, coins, requestId);
        } catch (DuplicateKeyException e) {
            throw conflict("They have already received a welcome gift.");
        }
        event(room.getId(), receiverId, "WELCOME_GIFT", "from " + senderId);
    }

    // ------------------------------------------------------------------ admin: rooms

    public Map<String, Object> adminRooms(String status, String q, int page, int size) {
        int sz = Math.max(1, Math.min(100, size));
        int pg = Math.max(0, page);
        StringBuilder where = new StringBuilder(" where 1 = 1");
        List<Object> args = new ArrayList<>();
        args.add(config().eligibilityDays());
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            where.append(" and nr.status = ?");
            args.add(status.toUpperCase());
        }
        List<Object> countArgs = new ArrayList<>();
        String countWhere = where.toString();
        countArgs.addAll(args.subList(1, args.size()));
        if (q != null && !q.isBlank()) {
            String like = "%" + q.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            String frag = " and (nr.name ilike ? or u.full_name ilike ? or cast(nr.room_number as text) = ? or cast(nr.room_id as text) = ?)";
            where.append(frag);
            countWhere += frag;
            for (Object o : new Object[]{like, like, q.trim(), q.trim()}) {
                args.add(o);
                countArgs.add(o);
            }
        }
        Long total = jdbc.queryForObject("select count(*) from newbie_rooms nr left join users u on u.id = nr.host_id" + countWhere, Long.class, countArgs.toArray());
        args.add(sz);
        args.add((long) pg * sz);
        List<Map<String, Object>> rows = jdbc.query(ROOM_SELECT + where + " order by (nr.status = 'ACTIVE') desc, nr.created_at desc limit ? offset ?", (rs, i) -> row(rs), args.toArray());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", rows);
        out.put("page", pg);
        out.put("size", sz);
        out.put("totalElements", total == null ? 0 : total);
        return out;
    }

    @Transactional
    public Map<String, Object> setRoomStatus(Long roomId, String status) {
        jdbc.query("select pg_advisory_xact_lock(?)", rs -> null, LOCK);
        Map<String, Object> room = one(roomId);
        if ("ACTIVE".equals(status)) {
            if (!"ACTIVE".equals(room.get("status")) && activeCount() >= config().maxRooms()) throw conflict("You have reached the maximum number of welcome rooms.");
            // Opening a room again gives it a fresh lifetime
            jdbc.update("update newbie_rooms set status = 'ACTIVE', closed_at = null, host_absent_since = null, expires_at = now() + make_interval(hours => ?) where room_id = ?", config().roomLifetimeHours(), roomId);
            jdbc.update("update voice_rooms set status = 'ACTIVE' where id = ?", roomId);
        } else {
            closeRoomRows(roomId, status);
        }
        event(roomId, null, "STATUS_" + status, null);
        return one(roomId);
    }

    private void closeRoomRows(Long roomId, String newbieStatus) {
        jdbc.update("update newbie_rooms set status = ?, closed_at = now() where room_id = ?", newbieStatus, roomId);
        jdbc.update("update voice_rooms set status = 'CLOSED' where id = ?", roomId);
        jdbc.update("update voice_room_participants set status = 'LEFT', left_at = now() where room_id = ? and status = 'JOINED'", roomId);
    }

    /** Deleting a welcome room closes it for good and removes it from the list (its numbers are never reused). */
    @Transactional
    public void deleteRoom(Long roomId) {
        one(roomId);
        closeRoomRows(roomId, "CLOSED");
        jdbc.update("update newbie_user_status set room_id = null where room_id = ?", roomId);
        jdbc.update("delete from newbie_rooms where room_id = ?", roomId);
        event(null, null, "ROOM_DELETED", String.valueOf(roomId));
    }

    @Transactional
    public Map<String, Object> setPriority(Long roomId, int priority) {
        if (priority < 0 || priority > 1000) throw bad("The priority must be between 0 and 1000.");
        jdbc.update("update newbie_rooms set priority = ? where room_id = ?", priority, roomId);
        return one(roomId);
    }

    @Transactional
    public Map<String, Object> changeHost(Long roomId, Long hostId, String by) {
        one(roomId);
        if (jdbc.queryForObject("select count(*) from users where id = ?", Long.class, hostId) == 0) throw bad("That user doesn't exist.");
        if (jdbc.queryForObject("select count(*) from newbie_rooms where host_id = ? and status = 'ACTIVE' and room_id <> ?", Long.class, hostId, roomId) > 0) throw conflict("That host already has another active welcome room.");
        jdbc.update("insert into newbie_room_hosts (user_id, added_by) values (?, ?) on conflict (user_id) do update set active = true", hostId, by);
        jdbc.update("update newbie_rooms set host_id = ?, host_absent_since = null where room_id = ?", hostId, roomId);
        jdbc.update("update voice_rooms set owner_id = ? where id = ?", hostId, roomId);
        event(roomId, hostId, "HOST_CHANGED", by);
        afterCommit(() -> roomState.onModeChanged(roomId));
        return one(roomId);
    }

    // ------------------------------------------------------------------ admin: hosts

    public List<Map<String, Object>> hosts() {
        return jdbc.queryForList("select h.user_id::text as \"userId\", u.full_name as name, u.avatar_url as \"avatarUrl\", u.public_id as \"publicId\", h.active, h.added_by as \"addedBy\", h.created_at as \"createdAt\","
                + " (select count(*) from newbie_rooms r where r.host_id = h.user_id) as rooms,"
                + " (select count(*) from newbie_rooms r where r.host_id = h.user_id and r.status = 'ACTIVE') as \"activeRooms\","
                + " (select count(*) from newbie_welcome_gifts g where g.host_id = h.user_id) as \"giftsSent\","
                + " (select coalesce(sum(g.coins), 0) from newbie_welcome_gifts g where g.host_id = h.user_id) as \"giftCoins\","
                + " (select count(distinct q.user_id) from newbie_room_participants q join newbie_rooms r on r.room_id = q.room_id where r.host_id = h.user_id and q.newbie) as \"newbiesWelcomed\","
                + " (select max(p.joined_at) from voice_room_participants p join newbie_rooms r on r.room_id = p.room_id where p.user_id = h.user_id) as \"lastActive\""
                + " from newbie_room_hosts h join users u on u.id = h.user_id order by h.created_at desc").stream().map(m -> {
            m.put("avatarUrl", MediaUrls.link((String) m.get("avatarUrl")));
            return m;
        }).toList();
    }

    @Transactional
    public List<Map<String, Object>> addHost(Long userId, String by) {
        if (userId == null || jdbc.queryForObject("select count(*) from users where id = ?", Long.class, userId) == 0) throw bad("That user doesn't exist.");
        jdbc.update("insert into newbie_room_hosts (user_id, added_by) values (?, ?) on conflict (user_id) do update set active = true", userId, by);
        return hosts();
    }

    @Transactional
    public List<Map<String, Object>> removeHost(Long userId) {
        jdbc.update("update newbie_room_hosts set active = false where user_id = ?", userId);
        return hosts();
    }

    // ------------------------------------------------------------------ admin: analytics

    public Map<String, Object> analytics() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalRooms", jdbc.queryForObject("select count(*) from newbie_rooms", Long.class));
        m.put("activeRooms", activeCount());
        m.put("newbiesWelcomed", jdbc.queryForObject("select count(*) from newbie_user_status where first_join_at is not null", Long.class));
        m.put("newbiesAssigned", jdbc.queryForObject("select count(*) from newbie_user_status where room_id is not null", Long.class));
        m.put("newbieRoomJoins", jdbc.queryForObject("select coalesce(sum(joins), 0) from newbie_room_participants where newbie", Long.class));
        Long welcomed = (Long) m.get("newbiesWelcomed");
        // A newbie counts as active once they have also been in a regular voice room
        Long converted = jdbc.queryForObject("select count(*) from newbie_user_status s where s.first_join_at is not null and exists"
                + " (select 1 from voice_room_participants p join voice_rooms v on v.id = p.room_id where p.user_id = s.user_id and not v.newbie)", Long.class);
        m.put("becameActive", converted);
        m.put("conversionPercent", welcomed == null || welcomed == 0 ? 0 : Math.round(converted * 1000.0 / welcomed) / 10.0);
        m.put("welcomeGiftsSent", jdbc.queryForObject("select count(*) from newbie_welcome_gifts", Long.class));
        m.put("welcomeGiftCoins", jdbc.queryForObject("select coalesce(sum(coins), 0) from newbie_welcome_gifts", Long.class));
        m.put("hosts", jdbc.queryForObject("select count(*) from newbie_room_hosts where active", Long.class));
        m.put("insideNow", jdbc.queryForObject("select count(*) from voice_room_participants p join newbie_rooms r on r.room_id = p.room_id where p.status = 'JOINED' and r.status = 'ACTIVE'", Long.class));
        m.put("avgVisitorsPerRoom", jdbc.queryForObject("select coalesce(round(avg(c), 1), 0) from (select count(*) as c from newbie_room_participants group by room_id) t", Double.class));
        m.put("recent", jdbc.queryForList("select e.kind, e.detail, e.created_at as \"at\", e.room_id::text as \"roomId\" from newbie_room_events e order by e.id desc limit 15"));
        return m;
    }

    // ------------------------------------------------------------------ upkeep

    /**
     * Every minute: close rooms that ran out of time or were closed by their host, and when a host has been away longer
     * than the grace time give the room to another host from the list, or close it if it is empty and nobody can take it.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void maintain() {
        try {
            doMaintain();
        } catch (Exception e) {
            log.warn("Newbie room upkeep failed: {}", e.getMessage());
        }
    }

    @Transactional
    public void doMaintain() {
        for (Long id : jdbc.queryForList("select nr.room_id from newbie_rooms nr where nr.status = 'ACTIVE' and nr.expires_at is not null and nr.expires_at < now()", Long.class)) {
            closeRoomRows(id, "EXPIRED");
            event(id, null, "EXPIRED", null);
        }
        for (Long id : jdbc.queryForList("select nr.room_id from newbie_rooms nr join voice_rooms vr on vr.id = nr.room_id where nr.status = 'ACTIVE' and vr.status <> 'ACTIVE'", Long.class)) {
            jdbc.update("update newbie_rooms set status = 'CLOSED', closed_at = now() where room_id = ?", id);
            event(id, null, "CLOSED", "by the host");
        }
        int grace = config().hostGraceMinutes();
        List<Map<String, Object>> live = jdbc.queryForList("select nr.room_id, nr.host_id, nr.host_absent_since,"
                + " exists (select 1 from voice_room_participants p where p.room_id = nr.room_id and p.user_id = nr.host_id and p.status = 'JOINED') as present,"
                + " (select count(*) from voice_room_participants p where p.room_id = nr.room_id and p.status = 'JOINED') as joined"
                + " from newbie_rooms nr where nr.status = 'ACTIVE'");
        for (Map<String, Object> r : live) {
            Long roomId = ((Number) r.get("room_id")).longValue();
            boolean present = Boolean.TRUE.equals(r.get("present"));
            java.sql.Timestamp since = (java.sql.Timestamp) r.get("host_absent_since");
            if (present) {
                if (since != null) jdbc.update("update newbie_rooms set host_absent_since = null where room_id = ?", roomId);
                continue;
            }
            if (since == null) {
                jdbc.update("update newbie_rooms set host_absent_since = now() where room_id = ?", roomId);
                continue;
            }
            if (since.toInstant().plusSeconds(grace * 60L).isAfter(Instant.now())) continue;
            Long next = freeHost();
            if (next != null) {
                jdbc.update("update newbie_rooms set host_id = ?, host_absent_since = null where room_id = ?", next, roomId);
                jdbc.update("update voice_rooms set owner_id = ? where id = ?", next, roomId);
                event(roomId, next, "HOST_REASSIGNED", "the host was away");
                afterCommit(() -> roomState.onModeChanged(roomId));
            } else if (((Number) r.get("joined")).longValue() == 0) {
                closeRoomRows(roomId, "CLOSED");
                event(roomId, null, "CLOSED", "no host and nobody inside");
            }
        }
    }
}
