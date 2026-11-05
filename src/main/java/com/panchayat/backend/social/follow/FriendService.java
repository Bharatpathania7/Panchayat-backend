package com.panchayat.backend.social.follow;

import com.panchayat.backend.admin.media.MediaUrls;
import com.panchayat.backend.chat.service.ChatSocialService;
import com.panchayat.backend.common.PublicIds;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Following and friends. Following someone is one way; two people who follow
 * each other are friends. For friends the app shows who's online, which room
 * they're in, what game is running there, and a feed of what they did lately.
 *
 * People who hide their online status (Privacy) never show as online or in a
 * room, and private rooms are never shown.
 */
@Service
public class FriendService {

    private final JdbcTemplate jdbc;
    private final ChatSocialService social;
    private final SimpMessagingTemplate messaging;

    @org.springframework.beans.factory.annotation.Autowired
    private com.panchayat.backend.progress.service.ExpService exp;

    public FriendService(JdbcTemplate jdbc, ChatSocialService social, SimpMessagingTemplate messaging) {
        this.jdbc = jdbc;
        this.social = social;
        this.messaging = messaging;
    }

    @Transactional
    public Map<String, Object> follow(Long me, Long other) {
        if (other == null || other.equals(me)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You can't follow yourself.");
        Integer exists = jdbc.queryForObject("select count(*) from users where id = ?", Integer.class, other);
        if (exists == null || exists == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That person doesn't exist.");
        Integer blocked = jdbc.queryForObject(
                "select count(*) from user_blocks where (blocker_id = ? and blocked_id = ?) or (blocker_id = ? and blocked_id = ?)",
                Integer.class, me, other, other, me);
        if (blocked != null && blocked > 0) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can't follow this person.");
        int added = jdbc.update("insert into user_follows (follower_id, followee_id) values (?, ?) on conflict do nothing", me, other);
        Map<String, Object> st = status(me, other);
        if (added > 0 && Boolean.TRUE.equals(st.get("friends"))) {
            // A new friend: a little EXP for both (capped per day)
            afterCommit(() -> {
                exp.add(me, "FRIEND", com.panchayat.backend.progress.service.ExpService.FRIEND);
                exp.add(other, "FRIEND", com.panchayat.backend.progress.service.ExpService.FRIEND);
            });
        }
        if (added > 0) {
            String email = jdbc.query("select email from users where id = ?", rs -> rs.next() ? rs.getString(1) : null, other);
            String name = jdbc.queryForObject("select coalesce(full_name, 'Someone') from users where id = ?", String.class, me);
            if (email != null) {
                Map<String, Object> note = new LinkedHashMap<>();
                note.put("type", Boolean.TRUE.equals(st.get("friends")) ? "FRIEND_NEW" : "FOLLOWED");
                note.put("byUserId", String.valueOf(me));
                note.put("byName", name);
                afterCommit(() -> messaging.convertAndSendToUser(email, "/queue/notify", (Object) note));
            }
        }
        return st;
    }

    @Transactional
    public Map<String, Object> unfollow(Long me, Long other) {
        jdbc.update("delete from user_follows where follower_id = ? and followee_id = ?", me, other);
        return status(me, other);
    }

    /** Whether you follow them, they follow you, and their counts. */
    @Transactional(readOnly = true)
    public Map<String, Object> status(Long me, Long other) {
        boolean iFollow = n("select count(*) from user_follows where follower_id = ? and followee_id = ?", me, other) > 0;
        boolean followsMe = n("select count(*) from user_follows where follower_id = ? and followee_id = ?", other, me) > 0;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", String.valueOf(other));
        m.put("following", iFollow);
        m.put("followsYou", followsMe);
        m.put("friends", iFollow && followsMe);
        m.putAll(counts(other));
        return m;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> counts(Long userId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("followers", n("select count(*) from user_follows where followee_id = ?", userId));
        m.put("followingCount", n("select count(*) from user_follows where follower_id = ?", userId));
        m.put("friendsCount", n("select count(*) from user_follows f join user_follows g on g.follower_id = f.followee_id and g.followee_id = f.follower_id where f.follower_id = ?", userId));
        return m;
    }

    /**
     * kind: friends (mutual), following, followers. Each person comes with
     * online, lastSeenAt and, for friends who show it, the public room they're in
     * and the game running there.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> list(Long me, String kind, int limit) {
        String k = kind == null ? "friends" : kind.toLowerCase(Locale.ROOT);
        String sql = switch (k) {
            case "following" -> "select f.followee_id as id from user_follows f where f.follower_id = ? order by f.created_at desc limit ?";
            case "followers" -> "select f.follower_id as id from user_follows f where f.followee_id = ? order by f.created_at desc limit ?";
            default -> "select f.followee_id as id from user_follows f join user_follows g on g.follower_id = f.followee_id and g.followee_id = f.follower_id"
                    + " where f.follower_id = ? order by f.created_at desc limit ?";
        };
        List<Long> ids = jdbc.queryForList(sql, Long.class, me, Math.max(1, Math.min(limit, 500)));
        Set<String> online = social.visibleOnlineEmails();
        List<Map<String, Object>> people = new ArrayList<>();
        for (Long id : ids) people.add(card(id, online, "friends".equals(k) || "following".equals(k)));
        // Online first, then people in rooms
        people.sort(Comparator.comparing((Map<String, Object> p) -> !Boolean.TRUE.equals(p.get("online")))
                .thenComparing(p -> p.get("room") == null));
        Map<String, Object> out = new LinkedHashMap<>(counts(me));
        out.put("kind", k);
        out.put("people", people);
        out.put("onlineCount", people.stream().filter(p -> Boolean.TRUE.equals(p.get("online"))).count());
        return out;
    }

    private Map<String, Object> card(Long id, Set<String> online, boolean withRoom) {
        Map<String, Object> u = jdbc.queryForMap(
                "select id, full_name, avatar_url, email, hide_online, case when hide_online then null else last_seen_at end as last_seen_at from users where id = ?", id);
        boolean hidden = Boolean.TRUE.equals(u.get("hide_online"));
        String email = (String) u.get("email");
        boolean on = !hidden && email != null && online.contains(email.toLowerCase(Locale.ROOT));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", String.valueOf(id));
        m.put("publicId", PublicIds.user(id));
        m.put("name", u.get("full_name"));
        m.put("avatarUrl", MediaUrls.link((String) u.get("avatar_url")));
        m.put("online", on);
        m.put("lastSeenAt", u.get("last_seen_at") == null ? null : u.get("last_seen_at").toString());
        m.put("room", withRoom && !hidden ? roomOf(id) : null);
        return m;
    }

    /** The public room someone is in now, unless they hide their online status. */
    public Map<String, Object> publicRoomOf(Long userId) {
        Boolean hidden = jdbc.queryForObject("select hide_online from users where id = ?", Boolean.class, userId);
        return Boolean.TRUE.equals(hidden) ? null : roomOf(userId);
    }

    /**
     * "Active now" for the chat screen: people you chat with or follow who are
     * online (and show it), each with the room they're in. People in a room first.
     */
    /**
     * "Active now" for the chat screen: your friends (you follow each other) who
     * are online and show it, each with the room they're in. People in a room first.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> activeNow(Long me, int limit) {
        List<Long> ids = jdbc.queryForList(
                "select a.followee_id from user_follows a join user_follows b on b.follower_id = a.followee_id and b.followee_id = a.follower_id"
                        + " where a.follower_id = ?", Long.class, me);
        Set<String> online = social.visibleOnlineEmails();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Long id : ids) {
            if (id.equals(me)) continue;
            Integer blocked = jdbc.queryForObject("select count(*) from user_blocks where (blocker_id = ? and blocked_id = ?) or (blocker_id = ? and blocked_id = ?)", Integer.class, me, id, id, me);
            if (blocked != null && blocked > 0) continue;
            Map<String, Object> c = card(id, online, true);
            if (Boolean.TRUE.equals(c.get("online"))) out.add(c);
        }
        out.sort(Comparator.comparing((Map<String, Object> p) -> p.get("room") == null));
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    /** The public room someone is in right now, and the game running there (or null). */
    private Map<String, Object> roomOf(Long userId) {
        List<Map<String, Object>> r = jdbc.queryForList(
                "select v.id, v.name, v.room_code, v.room_type, v.category, v.cover_image_url from voice_room_participants p join voice_rooms v on v.id = p.room_id"
                        + " where p.user_id = ? and p.status = 'JOINED' and v.status = 'ACTIVE' and not coalesce(v.is_private, false)"
                        + " and coalesce(v.password, '') = '' order by p.joined_at desc limit 1", userId);
        if (r.isEmpty()) return null;
        Map<String, Object> v = r.get(0);
        Map<String, Object> m = new LinkedHashMap<>();
        long rid = ((Number) v.get("id")).longValue();
        m.put("roomId", String.valueOf(rid));
        m.put("roomCode", v.get("room_code"));
        m.put("name", v.get("name"));
        m.put("type", v.get("room_type"));
        m.put("coverUrl", MediaUrls.link((String) v.get("cover_image_url")));
        m.put("game", gameIn(rid, String.valueOf(v.get("room_type"))));
        return m;
    }

    /** What's being played in a room: a running game, a PK, an auction, or the room's own kind. */
    private String gameIn(long roomId, String type) {
        if (table("room_games") && n("select count(*) from room_games where room_id = ? and status in ('WAITING','PLAYING')", roomId) > 0) {
            List<String> g = jdbc.queryForList("select game from room_games where room_id = ? and status in ('WAITING','PLAYING') order by created_at desc limit 1", String.class, roomId);
            if (!g.isEmpty()) return label(g.get(0));
        }
        if (type == null) return null;
        String t = type.toUpperCase(Locale.ROOT);
        if (t.contains("KHARIDARI") || t.contains("AUCTION")) return "Kharidari";
        if (t.contains("WATCH") || t.contains("YOUTUBE")) return "Watch together";
        return null;
    }

    private static String label(String game) {
        return switch (game == null ? "" : game.toUpperCase(Locale.ROOT)) {
            case "JACKAROO" -> "Jackaroo";
            case "LUDO" -> "Ludo";
            default -> game;
        };
    }

    private final Map<String, Boolean> tables = new java.util.concurrent.ConcurrentHashMap<>();

    private boolean table(String name) {
        return tables.computeIfAbsent(name, t -> Boolean.TRUE.equals(jdbc.queryForObject("select to_regclass(?) is not null", Boolean.class, t)));
    }

    /**
     * What your friends did lately: posted a Moment, became a CP, sent a big
     * gift, and which rooms they're in now. Newest first.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> activity(Long me, int limit) {
        int lim = Math.max(1, Math.min(limit, 100));
        String friends = "select f.followee_id from user_follows f join user_follows g on g.follower_id = f.followee_id and g.followee_id = f.follower_id where f.follower_id = ?";
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from ("
                        + " select 'MOMENT' as type, m.user_id as uid, m.id::text as ref, left(coalesce(m.body, ''), 120) as detail, m.created_at as at"
                        + "   from moments m where m.deleted_at is null and m.user_id in (" + friends + ")"
                        + " union all"
                        + " select 'CP', c.user_a, c.id::text, ub.full_name, c.since from cp_couples c join users ub on ub.id = c.user_b"
                        + "   where c.ended_at is null and c.user_a in (" + friends + ")"
                        + " union all"
                        + " select 'GIFT', e.sender_id, e.gift_id, coalesce(ur.full_name, '') || '|' || e.gift_count || '|' || e.coins, e.created_at"
                        + "   from room_gift_events e join users ur on ur.id = e.receiver_id where e.coins >= 999 and e.sender_id in (" + friends + ")"
                        + "   and e.created_at > now() - interval '7 days'"
                        + " union all"
                        + " select 'ROOM', p.user_id, v.id::text, v.name, p.joined_at from voice_room_participants p join voice_rooms v on v.id = p.room_id"
                        + "   join users u on u.id = p.user_id"
                        + "   where p.status = 'JOINED' and v.status = 'ACTIVE' and not coalesce(v.is_private, false) and coalesce(v.password, '') = ''"
                        + "   and not u.hide_online and p.user_id in (" + friends + ")"
                        + ") a order by at desc limit ?",
                me, me, me, me, lim);
        Map<Long, Map<String, Object>> people = new HashMap<>();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            long uid = ((Number) r.get("uid")).longValue();
            Map<String, Object> who = people.computeIfAbsent(uid, id -> {
                Map<String, Object> u = jdbc.queryForMap("select full_name, avatar_url from users where id = ?", id);
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("userId", String.valueOf(id));
                p.put("name", u.get("full_name"));
                p.put("avatarUrl", MediaUrls.link((String) u.get("avatar_url")));
                return p;
            });
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", r.get("type"));
            m.put("user", who);
            m.put("ref", r.get("ref"));
            m.put("detail", r.get("detail"));
            m.put("at", r.get("at") == null ? null : r.get("at").toString());
            out.add(m);
        }
        return out;
    }

    private int n(String sql, Object... args) {
        Integer v = jdbc.queryForObject(sql, Integer.class, args);
        return v == null ? 0 : v;
    }

    private static void afterCommit(Runnable r) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    r.run();
                }
            });
        } else {
            r.run();
        }
    }
}
