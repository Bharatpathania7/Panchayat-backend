package com.panchayat.backend.games.rmcs;

import com.panchayat.backend.admin.media.MediaUrls;
import com.panchayat.backend.games.GameSwitches;
import com.panchayat.backend.voice.entity.VoiceRoom;
import com.panchayat.backend.voice.repository.VoiceRoomParticipantRepository;
import com.panchayat.backend.voice.repository.VoiceRoomRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Raja Mantri Chor Sipahi, played at a table inside a voice room (so everyone can talk on the mic as usual),
 * with chat and emotes. 4 or 5 chairs; any chair nobody takes is played by a bot.
 *
 * Each round every player gets a hidden card: Raja (1000), Mantri (800), Sipahi (500), Chor (0), and with 5 players also
 * Prajaa (300). Raja and Mantri show their cards. The Mantri must now point at the Chor among the players still hidden:
 * right, and the Mantri keeps 800 and the Chor gets 0; wrong, and the Chor steals the 800. Five rounds, most points wins.
 *
 * The server owns the cards: a phone only ever sees its own card until a card is shown (or the round ends).
 * Live changes go out as a small "ping" on /topic/voice-rooms/{id}/rmcs and each phone fetches its own view.
 */
@Service
public class RmcsService {

    public static final String GAME = "RMCS";
    static final int MIN_SEATS = 4, MAX_SEATS = 5, ROUNDS = 5;
    static final long DEAL_MS = 5_000, GUESS_MS = 25_000, RESULT_MS = 7_000, ENDED_MS = 90_000, IDLE_MS = 45_000, LOBBY_IDLE_MS = 10 * 60_000;
    static final List<String> EMOTES = List.of("😂", "😍", "😡", "😭", "🔥", "👏", "😎", "🤫", "🙏", "🎉", "💀", "🫡");
    static final Map<String, Integer> POINTS = Map.of("RAJA", 1000, "MANTRI", 800, "SIPAHI", 500, "PRAJAA", 300, "CHOR", 0);
    static final String[] BOT_NAMES = {"Bhola", "Gabbar", "Basanti", "Munna", "Chhotu", "Pappu", "Rinku", "Guddu", "Babli", "Sonu"};

    /** Tables that are not inside a voice room have ids from here up (a room's id is always smaller). */
    public static final long TABLE_BASE = 2_000_000_000_000L;
    static final int MAX_TABLES = 300;
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private final java.util.concurrent.atomic.AtomicLong nextTable = new java.util.concurrent.atomic.AtomicLong(TABLE_BASE);

    public static boolean isTable(long id) {
        return id >= TABLE_BASE;
    }

    enum Phase {LOBBY, DEAL, GUESS, RESULT, ENDED}

    static final class P {
        final long id;
        final String name;
        final String avatar;
        final boolean bot;
        boolean autopilot = false; // a person who left mid-game: a bot plays for them
        int score, gain;
        String role;
        long lastSeen = System.currentTimeMillis();

        P(long id, String name, String avatar, boolean bot) {
            this.id = id;
            this.name = name;
            this.avatar = avatar;
            this.bot = bot;
        }

        boolean human() {
            return !bot && !autopilot;
        }
    }

    static final class Game {
        final long roomId;
        boolean standalone = false, isPublic = true;
        String code;
        Phase phase = Phase.LOBBY;
        final List<P> seats = new ArrayList<>();
        int size = 4, round = 0;
        long hostId, endsAt, version = 1, phaseStarted = System.currentTimeMillis(), botActAt;
        Long picked; // who the Mantri pointed at
        boolean caught;
        final List<Map<String, Object>> chat = new ArrayList<>();
        long lastActivity = System.currentTimeMillis();

        Game(long roomId) {
            this.roomId = roomId;
        }
    }

    private final Map<Long, Game> games = new ConcurrentHashMap<>();
    private final Map<String, Long> idByEmail = new ConcurrentHashMap<>();
    private final Map<Long, Long> lastChat = new ConcurrentHashMap<>();
    private final VoiceRoomRepository rooms;
    private final VoiceRoomParticipantRepository participants;
    private final JdbcTemplate jdbc;
    private final SimpMessagingTemplate messaging;
    private final GameSwitches switches;
    private final Random rnd = new Random();
    private ScheduledExecutorService ticker;

    public RmcsService(VoiceRoomRepository rooms, VoiceRoomParticipantRepository participants, JdbcTemplate jdbc, SimpMessagingTemplate messaging, GameSwitches switches) {
        this.rooms = rooms;
        this.participants = participants;
        this.jdbc = jdbc;
        this.messaging = messaging;
        this.switches = switches;
    }

    @PostConstruct
    void start() {
        ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rmcs-ticker");
            t.setDaemon(true);
            return t;
        });
        ticker.scheduleWithFixedDelay(() -> {
            try {
                tick();
            } catch (Exception e) {
                // keep ticking
            }
        }, 500, 500, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        if (ticker != null) ticker.shutdownNow();
    }

    /* ---------------- numbers for the admin ---------------- */

    public Map<String, Object> stats() {
        int players = 0, bots = 0, inPlay = 0;
        for (Game g : games.values()) {
            synchronized (g) {
                for (P p : g.seats) {
                    if (p.bot) bots++;
                    else players++;
                }
                if (g.phase != Phase.LOBBY && g.phase != Phase.ENDED) inPlay++;
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tables", games.size());
        m.put("tablesInPlay", inPlay);
        m.put("players", players);
        m.put("bots", bots);
        return m;
    }

    /* ---------------- joining and leaving ---------------- */

    public Map<String, Object> view(Long roomId, String email) {
        Long uid = requireUser(email);
        if (!isTable(roomId)) requireInRoom(roomId, uid);
        Game g = games.get(roomId);
        if (g == null && isTable(roomId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That table is no longer there.");
        return build(roomId, g, uid);
    }

    public Map<String, Object> sit(Long roomId, String email) {
        Long uid = requireUser(email);
        switches.require(GAME);
        Game g;
        if (isTable(roomId)) {
            g = games.get(roomId);
            if (g == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That table is no longer there.");
        } else {
            requireInRoom(roomId, uid);
            g = games.computeIfAbsent(roomId, Game::new);
        }
        synchronized (g) {
            seat(g, uid);
        }
        return build(roomId, g, uid);
    }

    private void seat(Game g, long uid) {
        if (g.seats.stream().anyMatch(p -> p.id == uid)) return;
        if (g.phase != Phase.LOBBY) throw conflict("A game is going on. Sit for the next one.");
        long humans = g.seats.stream().filter(p -> !p.bot).count();
        if (humans >= g.size) throw conflict("All chairs are taken.");
        Map<String, Object> u = jdbc.queryForList("select full_name, avatar_url from users where id = ?", uid).stream().findFirst().orElse(Map.of());
        String name = u.get("full_name") == null ? "Khiladi" : String.valueOf(u.get("full_name"));
        g.seats.removeIf(p -> p.bot);
        g.seats.add(new P(uid, name, MediaUrls.link((String) u.get("avatar_url")), false));
        if (g.seats.stream().filter(p -> !p.bot).count() == 1) g.hostId = uid;
        touch(g);
    }

    /* ---------------- tables: a game on its own (no voice room), found by a code ---------------- */

    public Map<String, Object> createTable(String email) {
        Long uid = requireUser(email);
        switches.require(GAME);
        leaveTables(uid);
        if (games.values().stream().filter(x -> x.standalone).count() >= MAX_TABLES) throw conflict("Too many tables are open right now. Try again in a moment.");
        Game g = new Game(nextTable.incrementAndGet());
        g.standalone = true;
        g.code = newCode();
        games.put(g.roomId, g);
        synchronized (g) {
            seat(g, uid);
        }
        return build(g.roomId, g, uid);
    }

    public boolean hasTable(String code) {
        return code != null && games.values().stream().anyMatch(x -> x.standalone && code.equalsIgnoreCase(x.code));
    }

    public Map<String, Object> joinByCode(String email, String code) {
        Long uid = requireUser(email);
        switches.require(GAME);
        String c = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        Game g = games.values().stream().filter(x -> x.standalone && c.equals(x.code)).findFirst().orElse(null);
        if (g == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No table with that code.");
        return joinTable(g, uid);
    }

    public Map<String, Object> joinTableById(Long id, String email) {
        Long uid = requireUser(email);
        Game g = games.get(id);
        if (g == null || !g.standalone) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That table is no longer there.");
        return joinTable(g, uid);
    }

    private Map<String, Object> joinTable(Game g, Long uid) {
        synchronized (g) {
            if (!g.seats.stream().anyMatch(p -> p.id == uid)) leaveTables(uid);
            seat(g, uid);
        }
        return build(g.roomId, g, uid);
    }

    public List<Map<String, Object>> openTables() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Game g : games.values()) {
            synchronized (g) {
                if (!g.standalone || !g.isPublic || g.phase != Phase.LOBBY || g.seats.isEmpty()) continue;
                long humans = g.seats.stream().filter(p -> !p.bot).count();
                if (humans >= g.size) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", String.valueOf(g.roomId));
                m.put("host", g.seats.get(0).name);
                m.put("players", humans);
                m.put("max", g.size);
                out.add(m);
            }
        }
        return out;
    }

    public Map<String, Object> setPublic(Long roomId, String email, boolean pub) {
        Long uid = requireUser(email);
        Game g = games.get(roomId);
        if (g == null || !g.standalone) throw conflict("This is not a table game.");
        synchronized (g) {
            if (g.hostId != uid) throw conflict("Only the host can change this.");
            g.isPublic = pub;
            touch(g);
        }
        return build(roomId, g, uid);
    }

    private void leaveTables(long uid) {
        for (Game g : games.values()) {
            if (!g.standalone) continue;
            synchronized (g) {
                removeHuman(g, uid);
            }
        }
    }

    private String newCode() {
        for (int i = 0; i < 20; i++) {
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < 6; k++) sb.append(CODE_CHARS.charAt(rnd.nextInt(CODE_CHARS.length())));
            String c = sb.toString();
            if (games.values().stream().noneMatch(x -> c.equals(x.code))) return c;
        }
        throw conflict("Couldn't make a table code. Try again.");
    }

    private String topicOf(Game g) {
        return g.standalone ? "/topic/rmcs/t/" + g.roomId : "/topic/voice-rooms/" + g.roomId + "/rmcs";
    }

    public Map<String, Object> stand(Long roomId, String email) {
        Long uid = requireUser(email);
        Game g = games.get(roomId);
        if (g != null) synchronized (g) {
            removeHuman(g, uid);
        }
        return build(roomId, g, uid);
    }

    private void removeHuman(Game g, long uid) {
        P p = g.seats.stream().filter(x -> x.id == uid).findFirst().orElse(null);
        if (p == null) return;
        if (g.phase == Phase.LOBBY || g.phase == Phase.ENDED) g.seats.remove(p);
        else p.autopilot = true; // the game goes on, a bot plays this chair
        if (g.hostId == uid) g.hostId = g.seats.stream().filter(P::human).map(x -> x.id).findFirst().orElse(0L);
        touch(g);
    }

    public Map<String, Object> setSize(Long roomId, String email, int size) {
        Long uid = requireUser(email);
        Game g = games.get(roomId);
        if (g == null) throw conflict("Sit at the table first.");
        synchronized (g) {
            if (g.hostId != uid) throw conflict("Only the host can change this.");
            if (g.phase != Phase.LOBBY) throw conflict("The game has started.");
            if (size < MIN_SEATS || size > MAX_SEATS) size = MIN_SEATS;
            if (g.seats.stream().filter(p -> !p.bot).count() > size) throw conflict("More people are sitting than that.");
            g.size = size;
            touch(g);
        }
        return build(roomId, g, uid);
    }

    public Map<String, Object> begin(Long roomId, String email) {
        Long uid = requireUser(email);
        Game g = games.get(roomId);
        if (g == null) throw conflict("Sit at the table first.");
        synchronized (g) {
            if (g.hostId != uid) throw conflict("Only the host can start the game.");
            if (g.phase != Phase.LOBBY && g.phase != Phase.ENDED) throw conflict("The game has already started.");
            if (g.phase == Phase.ENDED) g.seats.removeIf(p -> p.bot || p.autopilot);
            fillBots(g);
            for (P p : g.seats) p.score = 0;
            g.round = 0;
            nextRound(g, System.currentTimeMillis());
        }
        return build(roomId, g, uid);
    }

    public Map<String, Object> toLobby(Long roomId, String email) {
        Long uid = requireUser(email);
        Game g = games.get(roomId);
        if (g == null) throw conflict("Sit at the table first.");
        synchronized (g) {
            if (g.hostId != uid) throw conflict("Only the host can do this.");
            g.seats.removeIf(p -> p.bot || p.autopilot);
            g.phase = Phase.LOBBY;
            g.round = 0;
            for (P p : g.seats) {
                p.score = 0;
                p.gain = 0;
                p.role = null;
            }
            touch(g);
        }
        return build(roomId, g, uid);
    }

    private void fillBots(Game g) {
        List<String> names = new ArrayList<>(Arrays.asList(BOT_NAMES));
        Collections.shuffle(names, rnd);
        long botId = -1;
        while (g.seats.size() < g.size) g.seats.add(new P(botId--, names.remove(0), null, true));
    }

    /* ---------------- the pick ---------------- */

    public Map<String, Object> pick(Long roomId, String email, Long target) {
        Long uid = requireUser(email);
        Game g = games.get(roomId);
        if (g == null) throw conflict("No game here.");
        synchronized (g) {
            P me = g.seats.stream().filter(p -> p.id == uid).findFirst().orElse(null);
            if (me == null || g.phase != Phase.GUESS || !"MANTRI".equals(me.role) || g.picked != null) throw conflict("It is not your turn to choose.");
            P t = g.seats.stream().filter(p -> target != null && p.id == target).findFirst().orElse(null);
            if (t == null || t == me || "RAJA".equals(t.role)) throw conflict("Choose one of the hidden players.");
            decide(g, t, System.currentTimeMillis());
        }
        return build(roomId, g, uid);
    }

    private void decide(Game g, P target, long now) {
        P mantri = g.seats.stream().filter(p -> "MANTRI".equals(p.role)).findFirst().orElseThrow();
        g.picked = target.id;
        g.caught = "CHOR".equals(target.role);
        for (P p : g.seats) {
            int pts = POINTS.get(p.role);
            if ("MANTRI".equals(p.role) && !g.caught) pts = 0;
            if ("CHOR".equals(p.role) && !g.caught) pts = POINTS.get("MANTRI");
            p.gain = pts;
            p.score += pts;
        }
        g.phase = Phase.RESULT;
        g.endsAt = now + RESULT_MS;
        touch(g);
    }

    private void nextRound(Game g, long now) {
        g.round++;
        List<String> roles = new ArrayList<>(List.of("RAJA", "MANTRI", "SIPAHI", "CHOR"));
        if (g.seats.size() >= 5) roles.add("PRAJAA");
        Collections.shuffle(roles, rnd);
        for (int i = 0; i < g.seats.size(); i++) {
            g.seats.get(i).role = roles.get(i);
            g.seats.get(i).gain = 0;
        }
        g.picked = null;
        g.caught = false;
        g.phase = Phase.DEAL;
        g.endsAt = now + DEAL_MS;
        touch(g);
    }

    /* ---------------- chat and emotes ---------------- */

    public Map<String, Object> chat(Long roomId, String email, String text, String emote) {
        Long uid = requireUser(email);
        Game g = games.get(roomId);
        if (g == null) throw conflict("Sit at the table first.");
        long now = System.currentTimeMillis();
        synchronized (g) {
            P me = g.seats.stream().filter(p -> p.id == uid).findFirst().orElse(null);
            if (me == null) throw conflict("Sit at the table first.");
            Long prev = lastChat.get(uid);
            if (prev != null && now - prev < 600) return Map.of("ok", true);
            lastChat.put(uid, now);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("g", "rmcs");
            m.put("from", String.valueOf(uid));
            m.put("name", me.name);
            m.put("t", now);
            if (emote != null && EMOTES.contains(emote)) {
                m.put("k", "emote");
                m.put("e", emote);
            } else {
                String clean = text == null ? "" : text.replaceAll("\\p{Cntrl}", " ").strip();
                if (clean.isEmpty()) return Map.of("ok", true);
                if (clean.length() > 140) clean = clean.substring(0, 140);
                m.put("k", "chat");
                m.put("text", clean);
                g.chat.add(m);
                while (g.chat.size() > 40) g.chat.remove(0);
            }
            g.lastActivity = now;
            messaging.convertAndSend(topicOf(g), (Object) m);
        }
        return Map.of("ok", true);
    }

    /* ---------------- the clock: timers and the bots ---------------- */

    private void tick() {
        long now = System.currentTimeMillis();
        for (Game g : games.values()) {
            synchronized (g) {
                // people who stopped asking for the game: a bot takes the chair (or they leave the lobby)
                for (P p : new ArrayList<>(g.seats)) {
                    if (p.human() && now - p.lastSeen > IDLE_MS) removeHuman(g, p.id);
                }
                if (g.seats.stream().noneMatch(P::human)) {
                    if (now - g.lastActivity > 30_000) games.remove(g.roomId);
                    continue;
                }
                if (g.phase == Phase.LOBBY && now - g.lastActivity > LOBBY_IDLE_MS) {
                    games.remove(g.roomId);
                    continue;
                }
                switch (g.phase) {
                    case DEAL -> {
                        if (now >= g.endsAt) {
                            g.phase = Phase.GUESS;
                            g.endsAt = now + GUESS_MS;
                            g.botActAt = now + 2500 + rnd.nextInt(3500);
                            touch(g);
                        }
                    }
                    case GUESS -> {
                        P mantri = g.seats.stream().filter(p -> "MANTRI".equals(p.role)).findFirst().orElse(null);
                        if (mantri == null) break;
                        boolean auto = !mantri.human() && now >= g.botActAt;
                        if (auto || now >= g.endsAt) decide(g, randomHidden(g, mantri), now);
                    }
                    case RESULT -> {
                        if (now >= g.endsAt) {
                            if (g.round >= ROUNDS) {
                                g.phase = Phase.ENDED;
                                g.endsAt = now + ENDED_MS;
                                touch(g);
                            } else nextRound(g, now);
                        }
                    }
                    case ENDED -> {
                    }
                    default -> {
                    }
                }
            }
        }
    }

    private P randomHidden(Game g, P mantri) {
        List<P> hidden = g.seats.stream().filter(p -> p != mantri && !"RAJA".equals(p.role)).toList();
        return hidden.get(rnd.nextInt(hidden.size()));
    }

    /* ---------------- what a phone sees ---------------- */

    private Map<String, Object> build(Long roomId, Game g, Long uid) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("emotes", EMOTES);
        out.put("rounds", ROUNDS);
        out.put("enabled", switches.enabled(GAME));
        if (g == null) {
            out.put("phase", "NONE");
            out.put("seats", List.of());
            out.put("size", 4);
            out.put("serverTime", System.currentTimeMillis());
            return out;
        }
        synchronized (g) {
            P me = uid == null ? null : g.seats.stream().filter(p -> p.id == uid).findFirst().orElse(null);
            if (me != null) me.lastSeen = System.currentTimeMillis();
            boolean over = g.phase == Phase.RESULT || g.phase == Phase.ENDED;
            out.put("phase", g.phase.name());
            out.put("roomId", String.valueOf(g.roomId));
            if (g.standalone) {
                Map<String, Object> tb = new LinkedHashMap<>();
                tb.put("code", g.code);
                tb.put("public", g.isPublic);
                out.put("table", tb);
            }
            out.put("round", g.round);
            out.put("size", g.size);
            out.put("host", String.valueOf(g.hostId));
            out.put("endsAt", g.endsAt);
            out.put("serverTime", System.currentTimeMillis());
            out.put("version", g.version);
            out.put("picked", g.picked == null ? null : String.valueOf(g.picked));
            out.put("caught", g.caught);
            List<Map<String, Object>> seats = new ArrayList<>();
            for (P p : g.seats) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("id", String.valueOf(p.id));
                s.put("name", p.name);
                if (p.avatar != null) s.put("avatarUrl", p.avatar);
                s.put("bot", p.bot || p.autopilot);
                s.put("score", p.score);
                s.put("gain", p.gain);
                boolean show = over || (g.phase == Phase.GUESS && ("RAJA".equals(p.role) || "MANTRI".equals(p.role))) || (me != null && me.id == p.id);
                if (show && p.role != null && g.phase != Phase.LOBBY) s.put("role", p.role);
                seats.add(s);
            }
            out.put("seats", seats);
            out.put("chat", new ArrayList<>(g.chat));
            if (g.phase == Phase.ENDED) {
                int best = g.seats.stream().mapToInt(p -> p.score).max().orElse(0);
                out.put("winners", g.seats.stream().filter(p -> p.score == best).map(p -> String.valueOf(p.id)).toList());
            }
        }
        return out;
    }

    private void touch(Game g) {
        g.version++;
        g.lastActivity = System.currentTimeMillis();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("g", "rmcs");
        m.put("k", "ping");
        m.put("v", g.version);
        messaging.convertAndSend(topicOf(g), (Object) m);
    }

    private ResponseStatusException conflict(String msg) {
        return new ResponseStatusException(HttpStatus.CONFLICT, msg);
    }

    private Long requireUser(String email) {
        if (email == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in again.");
        Long id = idByEmail.get(email);
        if (id == null) {
            id = jdbc.query("select id from users where lower(email) = lower(?)", rs -> rs.next() ? rs.getLong(1) : null, email);
            if (id != null) idByEmail.put(email, id);
        }
        if (id == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in again.");
        return id;
    }

    private void requireInRoom(Long roomId, Long uid) {
        VoiceRoom room = rooms.findById(roomId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Voice room not found"));
        if (!"ACTIVE".equals(room.getStatus())) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Voice room is not available");
        if (participants.findByRoomIdAndUserIdAndStatus(roomId, uid, "JOINED").isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Join the room first.");
        }
    }
}
