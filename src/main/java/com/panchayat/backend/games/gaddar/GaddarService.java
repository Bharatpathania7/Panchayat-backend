package com.panchayat.backend.games.gaddar;

import com.panchayat.backend.admin.media.MediaUrls;
import com.panchayat.backend.games.gaddar.GaddarMaps.MapDef;
import com.panchayat.backend.games.gaddar.GaddarMaps.TaskDef;
import com.panchayat.backend.games.gaddar.GaddarMaps.VentDef;
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
import java.util.concurrent.atomic.AtomicLong;

/**
 * Gaon ka Gaddaar: a village social-deduction game played inside a voice room, with text chat only.
 * 4 to 10 players walk around one of three maps. Most are Gaonwale (villagers) who finish their kaam (tasks); one or two are
 * Gaddaar (traitors) who quietly remove villagers, sabotage the village (andhera, kuan sukha) and hide in vents (surang).
 * A body is reported, or anyone rings the Sarpanch's bell at the baithak spot; everyone chats and votes, and the Panchayat
 * sends one person out of the village.
 *
 * The server owns the game: positions, walls, tasks, kills, sabotage, vents, meetings and votes. Phones only send where the
 * player wants to walk (/app/voice-rooms/{id}/gaddar) and one-off actions (/app/voice-rooms/{id}/gaddar.act, gaddar.chat) and
 * draw what the server says (/topic/voice-rooms/{id}/gaddar for everyone, /user/queue/gaddar for the player's private role,
 * kaam and vent). The world updates about 15 times a second, and only when something moved.
 */
@Service
public class GaddarService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GaddarService.class);

    public static final double SPEED = 190;
    public static final int DEFAULT_MIN_PLAYERS = 4, MAX_PLAYERS = 10;
    static final long TICK_MS = 66;
    static final double RADIUS = GaddarMaps.RADIUS;
    static final double TASK_RANGE = 70, TASK_SECONDS = 3.0, KILL_RANGE = 85, REPORT_RANGE = 110, EMERGENCY_RANGE = 100;
    static final double FIX_RANGE = 75, FIX_SECONDS = 3.0, VENT_RANGE = 60, VENT_SEE = 170;
    static final long FIRST_KILL_MS = 12_000, EMERGENCY_AFTER_MS = 15_000, VOTE_MS = 45_000, RESULT_MS = 6_000, ENDED_MS = 60_000;
    static final long SAB_COOLDOWN_MS = 30_000, REACTOR_MS = 40_000, FIRST_SAB_MS = 20_000;
    static final long IDLE_MS = 20_000;
    static final int TASKS_PER_PLAYER = 4;
    static final Set<Integer> KILL_CDS = Set.of(15, 25, 40);

    /** Tables (games that are not inside a voice room) have ids from here up, so they never clash with a room's id. */
    public static final long TABLE_BASE = 1_000_000_000_000L;
    static final int MAX_TABLES = 300;
    static final long LOBBY_IDLE_MS = 60_000;
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    public static boolean isTable(long id) {
        return id >= TABLE_BASE;
    }

    enum Phase {LOBBY, PLAY, MEETING, ENDED}

    static final class P {
        final long id;
        final String email;
        String name;
        String avatar;
        int color;
        int hat, suit, acc; // how the villager looks (chosen in the lobby)
        double x, y;
        boolean alive = true, gone = false, gaddar = false, work = false, usedEmergency = false;
        double dx, dy;
        int ventId = 0, taskId = 0;
        long lastSeen = System.currentTimeMillis(), lastInput = 0, killReadyAt = 0, lastChat = 0, taskStart = 0;
        final Map<Integer, Double> tasks = new LinkedHashMap<>(); // task id -> progress 0..1 (1 = done)
        final List<String> fakeTasks = new ArrayList<>(); // what a gaddaar pretends to do
        Long vote; // null = not voted, 0 = skip, else a player id

        P(long id, String email) {
            this.id = id;
            this.email = email;
        }
    }

    record Body(long uid, double x, double y) {
    }

    /** An active sabotage: LIGHTS (andhera) or REACTOR (kuan sukha, with a deadline). */
    static final class Sab {
        final String type;
        final long endsAt;
        double progress;
        /** Who is holding the fix points right now (the first pump, the second pump; lights use the first). */
        boolean heldA, heldB;

        Sab(String type, long endsAt) {
            this.type = type;
            this.endsAt = endsAt;
        }
    }

    static final class Game {
        final long roomId;
        Phase phase = Phase.LOBBY;
        long hostId;
        final LinkedHashMap<Long, P> players = new LinkedHashMap<>();
        final List<Body> bodies = new ArrayList<>();
        long phaseEndsAt, startedAt, endedAt, lastMeSent, lastMembershipCheck, lastSnapAt;
        long version = 1, snapSig;
        String winner; // GAONWALE | GADDAAR
        Map<String, Object> meeting; // who called it, why, and once decided the result
        final List<Map<String, Object>> chat = new ArrayList<>();
        // What the host chose in the lobby
        String mapChoice = "random";
        int gaddars = 0; // 0 = by headcount
        int killCd = 25;
        MapDef map;
        Sab sab;
        long sabReadyAt;
        // A table: a game on its own, with a code to share, not inside a voice room
        boolean standalone;
        String code;
        boolean isPublic = true;

        Game(long roomId) {
            this.roomId = roomId;
        }
    }

    private final VoiceRoomRepository rooms;
    private final VoiceRoomParticipantRepository participants;
    private final JdbcTemplate jdbc;
    private final SimpMessagingTemplate messaging;
    private final com.panchayat.backend.games.GameSwitches switches;
    private final Map<Long, Game> games = new ConcurrentHashMap<>();
    private final Map<String, Long> idByEmail = new ConcurrentHashMap<>();
    private final Map<String, Long> codes = new ConcurrentHashMap<>();
    private final AtomicLong tableSeq = new AtomicLong(TABLE_BASE);
    private final Random random = new Random();
    private ScheduledExecutorService loop;
    /** How many players a game needs: 4. To try it alone on your own computer, set GADDAR_MIN_PLAYERS=1 (or 2). */
    @org.springframework.beans.factory.annotation.Value("${app.gaddar.min-players:4}")
    private int minPlayers = DEFAULT_MIN_PLAYERS;

    // How busy the game is, for the admin stats
    private final AtomicLong inputs = new AtomicLong(), snaps = new AtomicLong(), tickNanos = new AtomicLong(), tickCount = new AtomicLong(), tickMaxNanos = new AtomicLong();
    private volatile long statsSince = System.currentTimeMillis();

    public GaddarService(VoiceRoomRepository rooms, VoiceRoomParticipantRepository participants, JdbcTemplate jdbc, SimpMessagingTemplate messaging, com.panchayat.backend.games.GameSwitches switches) {
        this.switches = switches;
        this.rooms = rooms;
        this.participants = participants;
        this.jdbc = jdbc;
        this.messaging = messaging;
    }

    @PostConstruct
    void start() {
        loop = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "gaddar-loop");
            t.setDaemon(true);
            return t;
        });
        loop.scheduleAtFixedRate(() -> {
            try {
                tickAll();
            } catch (Throwable e) {
                // one bad tick must never stop the world
            }
        }, 500, TICK_MS, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        if (loop != null) loop.shutdownNow();
    }

    /** Games running now and how hard the loop works (admin: GET /api/v1/admin/manage/gaddar/stats). */
    public Map<String, Object> stats(boolean reset) {
        long now = System.currentTimeMillis();
        double secs = Math.max(1, (now - statsSince) / 1000.0);
        int players = 0;
        int playing = 0;
        for (Game g : games.values()) {
            players += g.players.size();
            if (g.phase == Phase.PLAY || g.phase == Phase.MEETING) playing++;
        }
        long count = Math.max(1, tickCount.get());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("games", games.size());
        m.put("gamesInPlay", playing);
        m.put("tables", games.values().stream().filter(x -> x.standalone).count());
        m.put("players", players);
        m.put("inputsPerSec", Math.round(inputs.get() / secs));
        m.put("snapshotsPerSec", Math.round(snaps.get() / secs));
        m.put("tickAvgMicros", tickNanos.get() / count / 1000);
        m.put("tickMaxMicros", tickMaxNanos.get() / 1000);
        m.put("tickBudgetMicros", TICK_MS * 1000);
        m.put("windowSeconds", Math.round(secs));
        if (reset) {
            inputs.set(0);
            snaps.set(0);
            tickNanos.set(0);
            tickCount.set(0);
            tickMaxNanos.set(0);
            statsSince = now;
        }
        return m;
    }

    /* ---------------- lobby (REST) ---------------- */

    public Map<String, Object> get(Long roomId, String email) {
        Game g = games.get(roomId);
        Long uid = userId(email);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("min", minPlayers);
        out.put("max", MAX_PLAYERS);
        if (g == null) {
            out.put("state", emptyState(roomId));
            return out;
        }
        synchronized (g) {
            if (g.map != null && g.phase != Phase.LOBBY) out.put("map", GaddarMaps.json(g.map));
            out.put("state", publicState(g));
            P me = uid == null ? null : g.players.get(uid);
            if (me != null) {
                me.lastSeen = System.currentTimeMillis();
                out.put("me", privateState(g, me));
            }
            out.put("snap", snapshot(g));
            out.put("chat", new ArrayList<>(g.chat));
        }
        return out;
    }

    public Map<String, Object> mapJson(String id) {
        if (!GaddarMaps.ALL.containsKey(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown map");
        return GaddarMaps.json(GaddarMaps.ALL.get(id));
    }

    public Map<String, Object> join(Long roomId, String email) {
        Long uid = requireUser(email);
        switches.require("GADDAR");
        Game g;
        if (isTable(roomId)) {
            g = games.get(roomId);
            if (g == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That game is no longer there.");
        } else {
            requireInRoom(roomId, uid);
            g = games.computeIfAbsent(roomId, Game::new);
        }
        synchronized (g) {
            if (g.players.containsKey(uid)) return get(roomId, email);
            addPlayer(g, uid, email);
        }
        return get(roomId, email);
    }

    private void addPlayer(Game g, Long uid, String email) {
        if (g.phase != Phase.LOBBY) throw conflict("A game is going on. Join the next one.");
        if (g.players.size() >= MAX_PLAYERS) throw conflict("The village is full (" + MAX_PLAYERS + " players).");
        P p = new P(uid, email);
        Map<String, Object> u = jdbc.queryForList("select full_name, avatar_url from users where id = ?", uid).stream().findFirst().orElse(Map.of());
        p.name = u.get("full_name") == null ? "Gaonwala" : String.valueOf(u.get("full_name"));
        p.avatar = MediaUrls.link((String) u.get("avatar_url"));
        p.color = freeColor(g);
        g.players.put(uid, p);
        if (g.players.size() == 1) g.hostId = uid;
        bump(g);
    }

    /* ---------------- tables: a game on its own (no voice room) ---------------- */

    /** Opens a new table with the caller as host. Anyone can join it with its code, and (if public) from the list. */
    public Map<String, Object> createTable(String email) {
        switches.require("GADDAR");
        Long uid = requireUser(email);
        leaveTables(uid);
        if (games.values().stream().filter(x -> x.standalone).count() >= MAX_TABLES) throw conflict("Too many games are open right now. Try again in a moment.");
        long id = tableSeq.incrementAndGet();
        Game g = new Game(id);
        g.standalone = true;
        g.code = newCode();
        games.put(id, g);
        codes.put(g.code, id);
        synchronized (g) {
            addPlayer(g, uid, email);
        }
        return get(id, email);
    }

    public boolean hasTable(String code) {
        return code != null && games.values().stream().anyMatch(x -> x.standalone && code.equalsIgnoreCase(x.code));
    }

    public Map<String, Object> joinByCode(String email, String code) {
        Long uid = requireUser(email);
        String c = code == null ? "" : code.strip().toUpperCase();
        Long id = codes.get(c);
        Game g = id == null ? null : games.get(id);
        if (g == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No game has that code.");
        if (!g.players.containsKey(uid)) leaveTables(uid);
        return join(id, email);
    }

    /** Public tables still waiting for players. */
    public List<Map<String, Object>> openTables() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Game g : games.values()) {
            synchronized (g) {
                if (!g.standalone || !g.isPublic || g.phase != Phase.LOBBY || g.players.isEmpty() || g.players.size() >= MAX_PLAYERS) continue;
                P host = g.players.get(g.hostId);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", String.valueOf(g.roomId));
                m.put("host", host == null ? "Someone" : host.name);
                m.put("players", g.players.size());
                m.put("max", MAX_PLAYERS);
                m.put("map", g.mapChoice);
                out.add(m);
            }
        }
        out.sort(Comparator.comparing((Map<String, Object> m) -> (Integer) m.get("players")).reversed());
        return out.size() > 50 ? out.subList(0, 50) : out;
    }

    /** One table at a time: opening or joining another one leaves the old one (if it isn't mid-game). */
    private void leaveTables(Long uid) {
        for (Game g : new ArrayList<>(games.values())) {
            if (!g.standalone) continue;
            synchronized (g) {
                if (g.players.containsKey(uid) && (g.phase == Phase.LOBBY || g.phase == Phase.ENDED)) removePlayer(g, uid);
            }
        }
    }

    private String newCode() {
        for (int tries = 0; tries < 50; tries++) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 6; i++) b.append(CODE_CHARS.charAt(random.nextInt(CODE_CHARS.length())));
            if (!codes.containsKey(b.toString())) return b.toString();
        }
        throw conflict("Couldn't make a game code. Try again.");
    }

    private void dropGame(Game g) {
        games.remove(g.roomId, g);
        if (g.code != null) codes.remove(g.code, g.roomId);
    }

    public Map<String, Object> leave(Long roomId, String email) {
        Long uid = requireUser(email);
        Game g = games.get(roomId);
        if (g == null) return get(roomId, email);
        synchronized (g) {
            removePlayer(g, uid);
        }
        return get(roomId, email);
    }

    /** The host's choices before the game: the map (or random), how many gaddaars (0 = by headcount) and the kill cooldown. */
    public Map<String, Object> settings(Long roomId, String email, String map, Integer gaddars, Integer killCd, Boolean isPublic) {
        Long uid = requireUser(email);
        Game g = games.get(roomId);
        if (g == null || !g.players.containsKey(uid)) throw conflict("Join the game first.");
        synchronized (g) {
            if (g.phase != Phase.LOBBY) throw conflict("The game has already started.");
            if (g.hostId != uid) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the host can change this.");
            if (map != null) {
                if (!"random".equals(map) && !GaddarMaps.ALL.containsKey(map)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown map.");
                g.mapChoice = map;
            }
            if (gaddars != null) {
                if (gaddars < 0 || gaddars > 2) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "1 or 2 gaddaars (0 = automatic).");
                g.gaddars = gaddars;
            }
            if (killCd != null) {
                if (!KILL_CDS.contains(killCd)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Kill cooldown: 15, 25 or 40 seconds.");
                g.killCd = killCd;
            }
            if (isPublic != null && g.standalone) g.isPublic = isPublic;
            bump(g);
        }
        return get(roomId, email);
    }

    public Map<String, Object> start(Long roomId, String email) {
        Long uid = requireUser(email);
        Game g = games.get(roomId);
        if (g == null || !g.players.containsKey(uid)) throw conflict("Join the game first.");
        synchronized (g) {
            if (g.phase != Phase.LOBBY) throw conflict("The game has already started.");
            if (g.hostId != uid) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the person who opened the game can start it.");
            if (g.players.size() < Math.max(1, minPlayers)) throw conflict("You need at least " + minPlayers + " players. Ask more people in the room to join.");
            beginRound(g);
        }
        return get(roomId, email);
    }

    /** After a game ends: the host (or anyone, once it is over) takes everyone back to the lobby. */
    public Map<String, Object> backToLobby(Long roomId, String email) {
        Long uid = requireUser(email);
        Game g = games.get(roomId);
        if (g == null || !g.players.containsKey(uid)) throw conflict("Join the game first.");
        synchronized (g) {
            if (g.phase == Phase.ENDED || (g.phase != Phase.LOBBY && g.hostId == uid)) resetToLobby(g);
        }
        return get(roomId, email);
    }

    /** Choose how your villager looks. Only numbers in range are kept. */
    public Map<String, Object> look(Long roomId, String email, Integer hat, Integer suit, Integer acc) {
        Long uid = requireUser(email);
        Game g = games.get(roomId);
        if (g == null) throw conflict("No game here.");
        synchronized (g) {
            P me = g.players.get(uid);
            if (me == null) throw conflict("Join the game first.");
            if (g.phase == Phase.PLAY || g.phase == Phase.MEETING) throw conflict("Change your look in the lobby.");
            me.hat = hat == null ? 0 : Math.max(0, Math.min(11, hat));
            me.suit = suit == null ? 0 : Math.max(0, Math.min(11, suit));
            me.acc = acc == null ? 0 : Math.max(0, Math.min(7, acc));
            bump(g);
        }
        return get(roomId, email);
    }

    public Map<String, Object> vote(Long roomId, String email, Long target) {
        Long uid = requireUser(email);
        Game g = games.get(roomId);
        if (g == null) throw conflict("No game here.");
        synchronized (g) {
            P me = g.players.get(uid);
            if (me == null || g.phase != Phase.MEETING || g.meeting == null || g.meeting.get("result") != null) throw conflict("Voting is not open.");
            if (!me.alive) throw conflict("Only people still in the village can vote.");
            if (me.vote != null) throw conflict("You already voted.");
            long t = target == null ? 0 : target;
            if (t != 0) {
                P tp = g.players.get(t);
                if (tp == null || !tp.alive) throw conflict("Choose someone still in the village.");
            }
            me.vote = t;
            bump(g);
            if (g.players.values().stream().filter(p -> p.alive && !p.gone).allMatch(p -> p.vote != null)) resolveMeeting(g, System.currentTimeMillis());
        }
        return get(roomId, email);
    }

    /* ---------------- input (STOMP) ---------------- */

    /** Where the player wants to walk (dx, dy in -1..1) and whether they are holding the Kaam button. Also keeps the player "here". */
    public void input(Long roomId, String email, double dx, double dy, boolean work) {
        Game g = games.get(roomId);
        Long uid = userId(email);
        if (g == null || uid == null) return;
        inputs.incrementAndGet();
        synchronized (g) {
            P p = g.players.get(uid);
            if (p == null || p.gone) return;
            long now = System.currentTimeMillis();
            p.lastSeen = now;
            if (now - p.lastInput < 25) return;
            p.lastInput = now;
            double len = Math.hypot(dx, dy);
            if (!Double.isFinite(len)) return;
            if (len > 1) {
                dx /= len;
                dy /= len;
            }
            p.dx = dx;
            p.dy = dy;
            p.work = work;
        }
    }

    public void act(Long roomId, String email, String action) {
        Game g = games.get(roomId);
        Long uid = userId(email);
        if (g == null || uid == null || action == null) return;
        synchronized (g) {
            P p = g.players.get(uid);
            if (p == null || p.gone) return;
            long now = System.currentTimeMillis();
            p.lastSeen = now;
            if (g.phase != Phase.PLAY) return;
            String[] parts = action.split(":", 2);
            String arg = parts.length > 1 ? parts[1] : "";
            switch (parts[0]) {
                case "kill" -> kill(g, p, now);
                case "report" -> {
                    if (p.alive && p.ventId == 0 && g.bodies.stream().anyMatch(b -> dist(p.x, p.y, b.x(), b.y()) <= REPORT_RANGE)) startMeeting(g, p, "report", now);
                }
                case "emergency" -> {
                    boolean reactor = g.sab != null && "REACTOR".equals(g.sab.type);
                    if (p.alive && p.ventId == 0 && !reactor && !p.usedEmergency && now - g.startedAt >= EMERGENCY_AFTER_MS
                            && dist(p.x, p.y, g.map.meeting().x(), g.map.meeting().y()) <= EMERGENCY_RANGE) {
                        p.usedEmergency = true;
                        startMeeting(g, p, "emergency", now);
                    }
                }
                case "sab" -> sabotage(g, p, arg, now);
                case "vent" -> vent(g, p, arg);
                case "tstart" -> taskStart(g, p, parseInt(arg), now);
                case "tdone" -> taskDone(g, p, parseInt(arg), now);
                case "tcancel" -> p.taskId = 0;
                case "lightsfix" -> {
                    // the switch-panel puzzle is solved on the phone; the server only checks the player stands at the box
                    if (g.sab != null && "LIGHTS".equals(g.sab.type) && !p.gaddar && p.alive && !p.gone
                            && dist(p.x, p.y, g.map.lightFix().x(), g.map.lightFix().y()) <= FIX_RANGE + 25) {
                        g.sab.progress = 1;
                    }
                }
                default -> {
                }
            }
        }
    }

    /** A line in the baithak's chat. Only living players, only during a meeting, and not too fast. */
    public void chat(Long roomId, String email, String text) {
        Game g = games.get(roomId);
        Long uid = userId(email);
        if (g == null || uid == null || text == null) return;
        String clean = text.replaceAll("\\p{Cntrl}", " ").strip();
        if (clean.isEmpty()) return;
        if (clean.length() > 140) clean = clean.substring(0, 140);
        synchronized (g) {
            P p = g.players.get(uid);
            long now = System.currentTimeMillis();
            boolean talk = g.phase == Phase.LOBBY || g.phase == Phase.ENDED || (g.phase == Phase.MEETING && g.meeting != null && g.meeting.get("result") == null && p != null && p.alive);
            if (p == null || p.gone || !talk) return;
            if (now - p.lastChat < 800) return;
            p.lastChat = now;
            p.lastSeen = now;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("g", "gaddar");
            m.put("k", "chat");
            m.put("from", String.valueOf(p.id));
            m.put("name", p.name);
            m.put("color", p.color);
            m.put("text", clean);
            m.put("t", now);
            g.chat.add(m);
            while (g.chat.size() > 60) g.chat.remove(0);
            send(g, m);
        }
    }

    /* ---------------- rules ---------------- */

    private void beginRound(Game g) {
        long now = System.currentTimeMillis();
        List<String> ids = new ArrayList<>(GaddarMaps.ALL.keySet());
        g.map = GaddarMaps.get("random".equals(g.mapChoice) ? ids.get(random.nextInt(ids.size())) : g.mapChoice);
        List<P> list = new ArrayList<>(g.players.values());
        Collections.shuffle(list, random);
        // Always fewer gaddaars than villagers, or the game would be over before it began
        int gaddars = g.gaddars > 0 ? Math.min(g.gaddars, Math.max(1, (list.size() - 1) / 2)) : (list.size() >= 7 ? 2 : 1);
        for (int i = 0; i < list.size(); i++) {
            P p = list.get(i);
            p.alive = true;
            p.gone = false;
            p.gaddar = i < gaddars;
            p.work = false;
            p.dx = p.dy = 0;
            p.vote = null;
            p.usedEmergency = false;
            p.ventId = 0;
            p.taskId = 0;
            p.lastSeen = now;
            p.tasks.clear();
            p.fakeTasks.clear();
            List<TaskDef> pool = new ArrayList<>(g.map.tasks());
            Collections.shuffle(pool, random);
            if (!p.gaddar) {
                for (int k = 0; k < Math.min(TASKS_PER_PLAYER, pool.size()); k++) p.tasks.put(pool.get(k).id(), 0.0);
            } else {
                for (int k = 0; k < Math.min(3, pool.size()); k++) p.fakeTasks.add(pool.get(k).name());
            }
            p.killReadyAt = now + FIRST_KILL_MS;
        }
        placeAtMeeting(g);
        g.bodies.clear();
        g.chat.clear();
        g.meeting = null;
        g.winner = null;
        g.sab = null;
        g.sabReadyAt = now + FIRST_SAB_MS;
        g.phase = Phase.PLAY;
        g.startedAt = now;
        g.lastMeSent = 0;
        g.lastSnapAt = 0;
        bump(g);
        sendAllPrivate(g);
    }

    private void placeAtMeeting(Game g) {
        int i = 0, n = g.players.size();
        for (P p : g.players.values()) {
            double a = (2 * Math.PI * i++) / Math.max(1, n);
            p.x = g.map.meeting().x() + Math.cos(a) * 70;
            p.y = g.map.meeting().y() + Math.sin(a) * 70;
            p.ventId = 0;
        }
    }

    private void kill(Game g, P killer, long now) {
        if (!killer.gaddar || !killer.alive || killer.ventId != 0 || now < killer.killReadyAt) return;
        P victim = null;
        double best = KILL_RANGE;
        for (P v : g.players.values()) {
            if (v.alive && !v.gaddar && !v.gone) {
                double d = dist(killer.x, killer.y, v.x, v.y);
                if (d <= best) {
                    best = d;
                    victim = v;
                }
            }
        }
        if (victim == null) return;
        victim.alive = false;
        victim.work = false;
        victim.taskId = 0;
        g.bodies.add(new Body(victim.id, victim.x, victim.y));
        killer.x = victim.x;
        killer.y = victim.y;
        killer.killReadyAt = now + g.killCd * 1000L;
        bump(g);
        sendPrivate(g, victim);
        sendPrivate(g, killer);
        checkWin(g, now);
    }

    /* ---- sabotage ---- */

    private void sabotage(Game g, P p, String kind, long now) {
        if (!p.gaddar || !p.alive || g.sab != null || now < g.sabReadyAt) return;
        if ("lights".equals(kind)) g.sab = new Sab("LIGHTS", 0);
        else if ("reactor".equals(kind)) g.sab = new Sab("REACTOR", now + REACTOR_MS);
        else return;
        bump(g);
        sendAllPrivate(g);
    }

    private void tickSabotage(Game g, double dt, long now) {
        Sab s = g.sab;
        if (s == null) return;
        if ("LIGHTS".equals(s.type)) {
            boolean held = g.players.values().stream().anyMatch(p -> fixing(p, g.map.lightFix().x(), g.map.lightFix().y()));
            s.heldA = held;
            s.progress = held ? s.progress + dt / FIX_SECONDS : (s.progress >= 1 ? 1 : 0);
        } else {
            boolean a = g.players.values().stream().anyMatch(p -> fixing(p, g.map.reactorA().x(), g.map.reactorA().y()));
            boolean b = g.players.values().stream().anyMatch(p -> fixing(p, g.map.reactorB().x(), g.map.reactorB().y()));
            s.heldA = a;
            s.heldB = b;
            s.progress = a && b ? s.progress + dt / FIX_SECONDS : 0;
            if (now >= s.endsAt) {
                end(g, "GADDAAR", now);
                return;
            }
        }
        if (s.progress >= 1) {
            g.sab = null;
            g.sabReadyAt = now + SAB_COOLDOWN_MS;
            bump(g);
            sendAllPrivate(g);
        }
    }

    private static boolean fixing(P p, double x, double y) {
        return !p.gaddar && p.alive && !p.gone && p.work && p.dx == 0 && p.dy == 0 && dist(p.x, p.y, x, y) <= FIX_RANGE;
    }

    /* ---- vents ---- */

    private List<VentDef> linked(Game g, int ventId) {
        VentDef v = g.map.vents().stream().filter(x -> x.id() == ventId).findFirst().orElse(null);
        if (v == null) return List.of();
        return g.map.vents().stream().filter(x -> x.group() == v.group()).sorted(Comparator.comparingInt(VentDef::id)).toList();
    }

    private void vent(Game g, P p, String what) {
        if (!p.gaddar || !p.alive) return;
        switch (what) {
            case "" -> {
                if (p.ventId != 0) return;
                VentDef best = null;
                double bd = VENT_RANGE;
                for (VentDef v : g.map.vents()) {
                    double d = dist(p.x, p.y, v.x(), v.y());
                    if (d <= bd) {
                        bd = d;
                        best = v;
                    }
                }
                if (best == null) return;
                p.ventId = best.id();
                p.x = best.x();
                p.y = best.y();
                p.dx = p.dy = 0;
                p.work = false;
                seen(g, p, best.x(), best.y(), true);
            }
            case "next", "prev" -> {
                if (p.ventId == 0) return;
                List<VentDef> ring = linked(g, p.ventId);
                if (ring.size() < 2) return;
                int i = 0;
                for (int k = 0; k < ring.size(); k++) if (ring.get(k).id() == p.ventId) i = k;
                VentDef to = ring.get(Math.floorMod(i + ("next".equals(what) ? 1 : -1), ring.size()));
                p.ventId = to.id();
                p.x = to.x();
                p.y = to.y();
            }
            case "exit" -> {
                if (p.ventId == 0) return;
                p.ventId = 0;
                seen(g, p, p.x, p.y, false);
            }
            default -> {
            }
        }
        sendPrivate(g, p);
    }

    /** Villagers close to a vent see a gaddaar go in or come out. */
    private void seen(Game g, P who, double x, double y, boolean in) {
        for (P q : g.players.values()) {
            if (q.gaddar || !q.alive || q.gone || dist(q.x, q.y, x, y) > VENT_SEE) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("g", "gaddar");
            m.put("k", "evt");
            m.put("e", "vent");
            m.put("in", in);
            m.put("x", Math.round(x));
            m.put("y", Math.round(y));
            m.put("name", who.name);
            m.put("color", who.color);
            try {
                messaging.convertAndSendToUser(q.email, "/queue/gaddar", (Object) m);
            } catch (Exception ignored) {
                // not listening
            }
        }
    }

    /* ---- kaam that has a little game (diyas in order, wires) ---- */

    private TaskDef taskDef(Game g, int id) {
        return g.map.tasks().stream().filter(t -> t.id() == id).findFirst().orElse(null);
    }

    private void taskStart(Game g, P p, int id, long now) {
        TaskDef d = taskDef(g, id);
        Double prog = p.tasks.get(id);
        if (d == null || prog == null || prog >= 1 || "hold".equals(d.kind()) || p.gaddar || p.ventId != 0) return;
        if (dist(p.x, p.y, d.x(), d.y()) > TASK_RANGE) return;
        p.taskId = id;
        p.taskStart = now;
    }

    private void taskDone(Game g, P p, int id, long now) {
        TaskDef d = taskDef(g, id);
        if (d == null || p.taskId != id || p.gaddar) return;
        long min = "wires".equals(d.kind()) || "align".equals(d.kind()) ? 2200 : 2000; // faster than a person can do it is refused
        if (now - p.taskStart < min || dist(p.x, p.y, d.x(), d.y()) > TASK_RANGE + 25) return;
        p.tasks.put(id, 1.0);
        p.taskId = 0;
        bump(g);
        sendPrivate(g, p);
        checkWin(g, now);
    }

    /* ---- meetings ---- */

    private void startMeeting(Game g, P caller, String why, long now) {
        g.phase = Phase.MEETING;
        g.phaseEndsAt = now + VOTE_MS;
        g.bodies.clear();
        g.sab = null;
        g.sabReadyAt = now + SAB_COOLDOWN_MS / 2;
        for (P p : g.players.values()) {
            p.vote = null;
            p.work = false;
            p.dx = p.dy = 0;
            p.ventId = 0;
            p.taskId = 0;
            for (Map.Entry<Integer, Double> t : p.tasks.entrySet()) if (t.getValue() < 1) t.setValue(0.0);
        }
        g.meeting = new LinkedHashMap<>();
        g.meeting.put("by", String.valueOf(caller.id));
        g.meeting.put("byName", caller.name);
        g.meeting.put("why", why);
        bump(g);
        sendAllPrivate(g);
    }

    private void resolveMeeting(Game g, long now) {
        Map<Long, Integer> count = new HashMap<>();
        int skips = 0;
        for (P p : g.players.values()) {
            if (!p.alive || p.gone || p.vote == null) continue;
            if (p.vote == 0) skips++;
            else count.merge(p.vote, 1, Integer::sum);
        }
        long top = 0;
        int topVotes = 0;
        boolean tie = false;
        for (Map.Entry<Long, Integer> e : count.entrySet()) {
            if (e.getValue() > topVotes) {
                top = e.getKey();
                topVotes = e.getValue();
                tie = false;
            } else if (e.getValue() == topVotes) {
                tie = true;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> tally = new LinkedHashMap<>();
        count.forEach((k, v) -> tally.put(String.valueOf(k), v));
        tally.put("skip", skips);
        result.put("tally", tally);
        if (topVotes > 0 && !tie && topVotes > skips) {
            P out = g.players.get(top);
            out.alive = false;
            result.put("ejected", String.valueOf(out.id));
            result.put("ejectedName", out.name);
            result.put("wasGaddar", out.gaddar);
        } else {
            result.put("ejected", null);
        }
        g.meeting.put("result", result);
        g.phaseEndsAt = now + RESULT_MS;
        bump(g);
        sendAllPrivate(g);
    }

    private void afterMeeting(Game g, long now) {
        g.meeting = null;
        g.bodies.clear();
        placeAtMeeting(g);
        for (P p : g.players.values()) {
            p.vote = null;
            p.killReadyAt = Math.max(p.killReadyAt, now + 15_000);
            p.lastSeen = now;
        }
        g.phase = Phase.PLAY;
        g.lastSnapAt = 0;
        bump(g);
        sendAllPrivate(g);
        checkWin(g, now);
    }

    private void checkWin(Game g, long now) {
        if (g.phase != Phase.PLAY && g.phase != Phase.MEETING) return;
        long gaddarsAlive = g.players.values().stream().filter(p -> p.gaddar && p.alive && !p.gone).count();
        long villagersAlive = g.players.values().stream().filter(p -> !p.gaddar && p.alive && !p.gone).count();
        String winner = null;
        if (gaddarsAlive == 0) winner = "GAONWALE";
        // (With fewer than 3 players, only possible when trying the game alone, the headcount doesn't decide it)
        else if (g.players.size() >= 3 && gaddarsAlive >= villagersAlive) winner = "GADDAAR";
        else if (taskPercent(g) >= 1.0 && g.players.values().stream().anyMatch(p -> !p.gaddar)) winner = "GAONWALE";
        if (winner != null) end(g, winner, now);
    }

    private void end(Game g, String winner, long now) {
        g.phase = Phase.ENDED;
        g.winner = winner;
        g.endedAt = now;
        g.phaseEndsAt = now + ENDED_MS;
        g.meeting = null;
        g.sab = null;
        for (P p : g.players.values()) {
            p.dx = p.dy = 0;
            p.work = false;
            p.ventId = 0;
            p.taskId = 0;
        }
        bump(g);
        sendAllPrivate(g);
    }

    private void resetToLobby(Game g) {
        g.players.values().removeIf(p -> p.gone);
        g.phase = Phase.LOBBY;
        g.bodies.clear();
        g.meeting = null;
        g.winner = null;
        g.sab = null;
        g.map = null;
        for (P p : g.players.values()) {
            p.alive = true;
            p.gaddar = false;
            p.ventId = 0;
            p.taskId = 0;
            p.tasks.clear();
            p.fakeTasks.clear();
        }
        if (!g.players.containsKey(g.hostId) && !g.players.isEmpty()) g.hostId = g.players.keySet().iterator().next();
        bump(g);
        sendAllPrivate(g);
    }

    private void removePlayer(Game g, long uid) {
        removePlayer(g, uid, "left");
    }

    private void removePlayer(Game g, long uid, String why) {
        P p = g.players.get(uid);
        if (p == null) return;
        log.info("Gaddar room {}: player {} leaves the game ({}), phase {}", g.roomId, uid, why, g.phase);
        long now = System.currentTimeMillis();
        if (g.phase == Phase.LOBBY || g.phase == Phase.ENDED) {
            g.players.remove(uid);
            if (g.hostId == uid && !g.players.isEmpty()) g.hostId = g.players.keySet().iterator().next();
            bump(g);
            if (g.players.isEmpty()) dropGame(g);
            return;
        }
        // Left in the middle of a game: leaves the village quietly (no body)
        p.gone = true;
        p.alive = false;
        p.work = false;
        p.ventId = 0;
        bump(g);
        if (g.phase == Phase.MEETING && g.meeting != null && g.meeting.get("result") == null
                && g.players.values().stream().filter(q -> q.alive && !q.gone).allMatch(q -> q.vote != null)) {
            resolveMeeting(g, now);
        }
        checkWin(g, now);
    }

    /* ---------------- the clock ---------------- */

    private void tickAll() {
        long t0 = System.nanoTime();
        long now = System.currentTimeMillis();
        for (Game g : new ArrayList<>(games.values())) {
            synchronized (g) {
                tick(g, now);
            }
        }
        long took = System.nanoTime() - t0;
        tickNanos.addAndGet(took);
        tickCount.incrementAndGet();
        tickMaxNanos.accumulateAndGet(took, Math::max);
    }

    private void tick(Game g, long now) {
        // Someone who left the room, or whose phone went quiet, leaves the game
        if (now - g.lastMembershipCheck > 5_000) {
            g.lastMembershipCheck = now;
            if (!g.standalone) {
                VoiceRoom room = rooms.findById(g.roomId).orElse(null);
                if (room == null || !"ACTIVE".equals(room.getStatus())) {
                    games.remove(g.roomId, g);
                    return;
                }
                for (P p : new ArrayList<>(g.players.values())) {
                    if (!p.gone && participants.findByRoomIdAndUserIdAndStatus(g.roomId, p.id, "JOINED").isEmpty()) removePlayer(g, p.id, "not in the room any more");
                }
            } else if (g.phase == Phase.LOBBY || g.phase == Phase.ENDED) {
                // The lobby phones ask for the game every few seconds; one that stopped has left
                for (P p : new ArrayList<>(g.players.values())) {
                    if (now - p.lastSeen > LOBBY_IDLE_MS) removePlayer(g, p.id, "left the lobby (quiet for " + (now - p.lastSeen) / 1000 + "s)");
                }
            }
        }
        if (g.phase == Phase.PLAY || g.phase == Phase.MEETING) {
            for (P p : new ArrayList<>(g.players.values())) {
                if (!p.gone && now - p.lastSeen > IDLE_MS) removePlayer(g, p.id, "phone went quiet for " + (now - p.lastSeen) / 1000 + "s");
            }
        }
        switch (g.phase) {
            case PLAY -> {
                double dt = TICK_MS / 1000.0;
                for (P p : g.players.values()) {
                    if (p.gone) continue;
                    move(g, p, dt);
                    work(g, p, dt);
                }
                tickSabotage(g, dt, now);
                if (g.phase != Phase.PLAY) return;
                checkWin(g, now);
                if (g.phase == Phase.PLAY) {
                    // Only send when something changed (a quiet village costs nothing), and at least once a second
                    long sig = signature(g);
                    if (sig != g.snapSig || now - g.lastSnapAt >= 1000) {
                        g.snapSig = sig;
                        g.lastSnapAt = now;
                        snaps.incrementAndGet();
                        send(g, snapshot(g));
                    }
                    if (now - g.lastMeSent >= 500) {
                        g.lastMeSent = now;
                        sendAllPrivate(g);
                    }
                }
            }
            case MEETING -> {
                if (g.meeting != null && g.meeting.get("result") == null && now >= g.phaseEndsAt) resolveMeeting(g, now);
                else if (g.meeting != null && g.meeting.get("result") != null && now >= g.phaseEndsAt) {
                    afterMeeting(g, now);
                    checkWin(g, now);
                }
            }
            case ENDED -> {
                if (now >= g.phaseEndsAt) resetToLobby(g);
            }
            case LOBBY -> {
                if (g.players.isEmpty() && now - g.lastMembershipCheck > 60_000) dropGame(g);
            }
        }
    }

    private long signature(Game g) {
        long h = 17;
        for (P p : g.players.values()) {
            if (p.gone || p.ventId != 0) continue;
            h = h * 31 + Math.round(p.x) * 7 + Math.round(p.y) * 13 + (p.alive ? 1 : 2);
        }
        h = h * 31 + g.bodies.size();
        if (g.sab != null) h = h * 31 + Math.round(g.sab.progress * 20) + g.sab.type.hashCode() + (g.sab.heldA ? 3 : 0) + (g.sab.heldB ? 5 : 0);
        return h * 31 + Math.round(taskPercent(g) * 1000);
    }

    private void move(Game g, P p, double dt) {
        if (p.ventId != 0 || p.taskId != 0 || (p.dx == 0 && p.dy == 0)) return;
        double nx = p.x + p.dx * SPEED * dt;
        double ny = p.y + p.dy * SPEED * dt;
        MapDef m = g.map;
        if (!p.alive) {
            // Ghosts float through walls
            p.x = clamp(nx, RADIUS, m.w() - RADIUS);
            p.y = clamp(ny, RADIUS, m.h() - RADIUS);
            return;
        }
        if (!GaddarMaps.blocked(m, nx, p.y)) p.x = clamp(nx, RADIUS, m.w() - RADIUS);
        if (!GaddarMaps.blocked(m, p.x, ny)) p.y = clamp(ny, RADIUS, m.h() - RADIUS);
    }

    /** Kaam that is done by standing there and holding the button. */
    private void work(Game g, P p, double dt) {
        if (p.gaddar || p.tasks.isEmpty() || p.ventId != 0) return;
        Integer active = null;
        if (p.work && p.dx == 0 && p.dy == 0) {
            for (Map.Entry<Integer, Double> t : p.tasks.entrySet()) {
                if (t.getValue() >= 1) continue;
                TaskDef def = taskDef(g, t.getKey());
                if (def != null && "hold".equals(def.kind()) && dist(p.x, p.y, def.x(), def.y()) <= TASK_RANGE) {
                    active = t.getKey();
                    break;
                }
            }
        }
        for (Map.Entry<Integer, Double> t : p.tasks.entrySet()) {
            if (t.getValue() >= 1) continue;
            TaskDef def = taskDef(g, t.getKey());
            if (def == null || !"hold".equals(def.kind())) continue;
            if (active != null && t.getKey().equals(active)) t.setValue(Math.min(1.0, t.getValue() + dt / TASK_SECONDS));
            else t.setValue(0.0);
        }
    }

    private double taskPercent(Game g) {
        int total = 0;
        int done = 0;
        for (P p : g.players.values()) {
            if (p.gaddar) continue;
            for (double v : p.tasks.values()) {
                total++;
                if (v >= 1) done++;
            }
        }
        return total == 0 ? 0 : (double) done / total;
    }

    /* ---------------- what phones see ---------------- */

    private Map<String, Object> emptyState(Long roomId) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("g", "gaddar");
        s.put("k", "state");
        s.put("roomId", String.valueOf(roomId));
        s.put("v", 0);
        s.put("phase", "NONE");
        s.put("players", List.of());
        s.put("min", minPlayers);
        s.put("max", MAX_PLAYERS);
        s.put("maps", GaddarMaps.catalogue());
        s.put("settings", Map.of("map", "random", "gaddars", 0, "killCd", 25));
        return s;
    }

    private Map<String, Object> publicState(Game g) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("g", "gaddar");
        s.put("k", "state");
        s.put("roomId", String.valueOf(g.roomId));
        s.put("v", g.version);
        s.put("phase", g.phase.name());
        s.put("host", String.valueOf(g.hostId));
        s.put("min", minPlayers);
        s.put("max", MAX_PLAYERS);
        s.put("serverTime", System.currentTimeMillis());
        s.put("endsAt", g.phase == Phase.MEETING || g.phase == Phase.ENDED ? g.phaseEndsAt : 0);
        s.put("maps", GaddarMaps.catalogue());
        s.put("settings", Map.of("map", g.mapChoice, "gaddars", g.gaddars, "killCd", g.killCd));
        if (g.standalone) s.put("table", Map.of("id", String.valueOf(g.roomId), "code", g.code, "public", g.isPublic));
        if (g.map != null && g.phase != Phase.LOBBY) s.put("mapId", g.map.id());
        boolean reveal = g.phase == Phase.ENDED;
        List<Map<String, Object>> list = new ArrayList<>();
        for (P p : g.players.values()) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", String.valueOf(p.id));
            o.put("name", p.name);
            if (p.avatar != null) o.put("avatarUrl", p.avatar);
            o.put("color", p.color);
            o.put("hat", p.hat);
            o.put("suit", p.suit);
            o.put("acc", p.acc);
            o.put("alive", p.alive);
            o.put("gone", p.gone);
            if (reveal) o.put("role", p.gaddar ? "GADDAR" : "GAONWALA");
            if (g.phase == Phase.MEETING) o.put("voted", p.vote != null);
            list.add(o);
        }
        s.put("players", list);
        s.put("taskPct", Math.round(taskPercent(g) * 1000) / 1000.0);
        if (g.winner != null) s.put("winner", g.winner);
        if (g.meeting != null) s.put("meeting", g.meeting);
        return s;
    }

    private Map<String, Object> snapshot(Game g) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("g", "gaddar");
        s.put("k", "snap");
        s.put("t", System.currentTimeMillis());
        List<Object[]> ps = new ArrayList<>();
        for (P p : g.players.values()) {
            if (p.gone || p.ventId != 0) continue; // someone in a vent is not on the map
            ps.add(new Object[]{String.valueOf(p.id), Math.round(p.x), Math.round(p.y), p.alive ? 1 : 0, (p.dx != 0 || p.dy != 0) ? 1 : 0});
        }
        s.put("p", ps);
        List<Object[]> bs = new ArrayList<>();
        for (Body b : g.bodies) bs.add(new Object[]{String.valueOf(b.uid()), Math.round(b.x()), Math.round(b.y())});
        s.put("b", bs);
        s.put("tp", Math.round(taskPercent(g) * 1000) / 1000.0);
        if (g.sab != null) s.put("sb", new Object[]{g.sab.type.substring(0, 1), g.sab.endsAt, Math.round(g.sab.progress * 100) / 100.0, g.sab.heldA ? 1 : 0, g.sab.heldB ? 1 : 0});
        return s;
    }

    private Map<String, Object> privateState(Game g, P p) {
        Map<String, Object> m = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        m.put("g", "gaddar");
        m.put("k", "me");
        boolean inGame = g.phase != Phase.LOBBY;
        if (inGame) m.put("role", p.gaddar ? "GADDAR" : "GAONWALA");
        if (inGame && p.gaddar) {
            List<String> mates = new ArrayList<>();
            for (P q : g.players.values()) if (q.gaddar && q.id != p.id) mates.add(String.valueOf(q.id));
            m.put("mates", mates);
            m.put("killIn", Math.max(0, p.killReadyAt - now));
            m.put("sabIn", g.sab != null ? -1 : Math.max(0, g.sabReadyAt - now));
            m.put("fake", p.fakeTasks);
            if (p.ventId != 0) {
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("id", p.ventId);
                v.put("x", Math.round(p.x));
                v.put("y", Math.round(p.y));
                v.put("links", linked(g, p.ventId).size());
                m.put("vent", v);
            }
        }
        List<Map<String, Object>> tasks = new ArrayList<>();
        if (g.map != null) {
            for (Map.Entry<Integer, Double> t : p.tasks.entrySet()) {
                TaskDef d = taskDef(g, t.getKey());
                if (d == null) continue;
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("id", d.id());
                o.put("name", d.name());
                o.put("kind", d.kind());
                o.put("x", d.x());
                o.put("y", d.y());
                o.put("p", Math.round(t.getValue() * 100) / 100.0);
                tasks.add(o);
            }
        }
        m.put("tasks", tasks);
        m.put("alive", p.alive);
        boolean reactor = g.sab != null && "REACTOR".equals(g.sab.type);
        m.put("canEmergency", !p.usedEmergency && p.alive && !reactor && g.phase == Phase.PLAY && now - g.startedAt >= EMERGENCY_AFTER_MS);
        if (p.vote != null) m.put("myVote", String.valueOf(p.vote));
        return m;
    }

    private void sendAllPrivate(Game g) {
        for (P p : g.players.values()) sendPrivate(g, p);
    }

    private void sendPrivate(Game g, P p) {
        try {
            messaging.convertAndSendToUser(p.email, "/queue/gaddar", (Object) privateState(g, p));
        } catch (Exception ignored) {
            // a phone that isn't listening yet
        }
    }

    private void send(Game g, Map<String, Object> payload) {
        messaging.convertAndSend(g.standalone ? "/topic/gaddar/t/" + g.roomId : "/topic/voice-rooms/" + g.roomId + "/gaddar", (Object) payload);
    }

    private void bump(Game g) {
        g.version++;
        send(g, publicState(g));
    }

    /* ---------------- helpers ---------------- */

    private int freeColor(Game g) {
        boolean[] used = new boolean[MAX_PLAYERS];
        for (P p : g.players.values()) if (p.color >= 0 && p.color < MAX_PLAYERS) used[p.color] = true;
        for (int i = 0; i < MAX_PLAYERS; i++) if (!used[i]) return i;
        return 0;
    }

    private Long userId(String email) {
        if (email == null) return null;
        Long cached = idByEmail.get(email);
        if (cached != null) return cached;
        Long id = jdbc.query("select id from users where lower(email) = lower(?)", rs -> rs.next() ? rs.getLong(1) : null, email);
        if (id != null) idByEmail.put(email, id);
        return id;
    }

    private Long requireUser(String email) {
        Long id = userId(email);
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

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.strip());
        } catch (Exception e) {
            return -1;
        }
    }

    private static ResponseStatusException conflict(String m) {
        return new ResponseStatusException(HttpStatus.CONFLICT, m);
    }

    private static double dist(double ax, double ay, double bx, double by) {
        return Math.hypot(ax - bx, ay - by);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
