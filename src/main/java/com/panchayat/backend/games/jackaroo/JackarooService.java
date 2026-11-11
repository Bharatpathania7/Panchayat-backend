package com.panchayat.backend.games.jackaroo;

import com.panchayat.backend.admin.media.MediaUrls;
import com.panchayat.backend.games.jackaroo.JackarooEngine.Move;
import com.panchayat.backend.games.jackaroo.JackarooEngine.Player;
import com.panchayat.backend.games.jackaroo.JackarooEngine.State;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Jackaroo in rooms, refereed by the server.
 *
 *  - Anyone in the room opens a table (one open game per room) and others sit
 *    down (2 or 4 players). The one who opened it starts the game.
 *  - Each turn lasts TURN_SECONDS; when time runs out the server plays the
 *    first legal move for you (or burns a card).
 *  - Everyone in the room sees the table on /topic/voice-rooms/{roomId}/game
 *    (no hands). Each player gets their own hand and legal moves on
 *    /user/queue/game.
 */
@Service
public class JackarooService {
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private com.panchayat.backend.progress.tasks.DailyTaskService dailyTasks;


    private static final Logger log = LoggerFactory.getLogger(JackarooService.class);
    public static final int TURN_SECONDS = 25;

    private final JdbcTemplate jdbc;
    private final SimpMessagingTemplate messaging;
    private final TransactionTemplate tx;
    private final ObjectMapper json = new ObjectMapper();

    @org.springframework.beans.factory.annotation.Autowired
    private com.panchayat.backend.progress.service.ExpService exp;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "jackaroo-timer");
        t.setDaemon(true);
        return t;
    });

    public JackarooService(JdbcTemplate jdbc, SimpMessagingTemplate messaging, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.messaging = messaging;
        this.tx = new TransactionTemplate(txm);
        timer.scheduleWithFixedDelay(this::timeouts, 3, 2, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        timer.shutdownNow();
    }

    /* ---------------- lobby ---------------- */

    @Transactional
    public Map<String, Object> open(Long roomId, Long me) {
        requireInRoom(roomId, me);
        List<Long> open = jdbc.queryForList("select id from room_games where room_id = ? and game = 'JACKAROO' and status in ('WAITING','PLAYING')", Long.class, roomId);
        if (!open.isEmpty()) return view(open.get(0), me);
        State s = new State();
        s.players.add(player(me, 0));
        s.last = "Table open. Waiting for players…";
        // A short code to share: friends join the table with it
        Long id = null;
        for (int attempt = 0; id == null && attempt < 5; attempt++) {
            try {
                id = jdbc.queryForObject("insert into room_games (room_id, game, created_by, state, code) values (?, 'JACKAROO', ?, ?::jsonb, ?) returning id",
                        Long.class, roomId, me, json.writeValueAsString(s), com.panchayat.backend.social.invites.InviteService.code(6));
            } catch (org.springframework.dao.DuplicateKeyException clash) {
                // the same code twice: pick another
            }
        }
        if (id == null) throw conflict("Couldn't open a table. Try again.");
        broadcast(id);
        return view(id, me);
    }

    @Transactional
    public Map<String, Object> sit(Long gameId, Long me, Integer seat) {
        Row g = lock(gameId);
        if (!"WAITING".equals(g.status)) throw conflict("This game already started.");
        requireInRoom(g.roomId, me);
        if (g.s.players.stream().anyMatch(p -> p.userId == me)) {
            // Changing seats
            if (seat != null) {
                if (seat < 0 || seat > 3) throw bad("Pick seat 1 to 4.");
                if (g.s.players.stream().anyMatch(p -> p.seat == seat && p.userId != me)) throw conflict("That seat is taken.");
                g.s.players.stream().filter(p -> p.userId == me).forEach(p -> p.seat = seat);
            }
        } else {
            if (g.s.players.size() >= 4) throw conflict("The table is full.");
            int at = seat != null && seat >= 0 && seat <= 3 && g.s.players.stream().noneMatch(p -> p.seat == seat) ? seat
                    : firstFree(g.s);
            g.s.players.add(player(me, at));
            g.s.last = g.s.players.get(g.s.players.size() - 1).name + " sat down";
        }
        save(g, null);
        return view(gameId, me);
    }

    @Transactional
    public Map<String, Object> leave(Long gameId, Long me) {
        Row g = lock(gameId);
        Optional<Player> p = g.s.players.stream().filter(x -> x.userId == me).findFirst();
        if (p.isEmpty()) return view(gameId, me);
        if ("WAITING".equals(g.status)) {
            g.s.players.remove(p.get());
            if (g.s.players.isEmpty()) {
                g.status = "CANCELLED";
            } else g.s.last = p.get().name + " left the table";
            save(g, null);
        } else if ("PLAYING".equals(g.status)) {
            Player x = p.get();
            x.left = true;
            g.s.discard.addAll(x.hand);
            x.hand.clear();
            g.s.last = x.name + " left the game";
            // The other team wins if one side is empty
            Set<Integer> teams = new HashSet<>();
            g.s.players.stream().filter(q -> !q.left).forEach(q -> teams.add(q.team));
            if (teams.size() <= 1) {
                g.s.winnerTeam = teams.isEmpty() ? null : teams.iterator().next();
                g.status = "FINISHED";
                if (g.s.winnerTeam != null) {
                    g.s.last += ". " + JackarooEngine.teamName(g.s, g.s.winnerTeam) + " won!";
                    List<Long> winners = g.s.players.stream().filter(q -> q.team == g.s.winnerTeam && !q.left).map(q -> q.userId).toList();
                    afterCommit(() -> winners.forEach(u -> exp.add(u, "GAME", com.panchayat.backend.progress.service.ExpService.GAME_WIN)));
                }
                g.s.players.forEach(q -> dailyTasks.track(q.userId, "PLAY_GAME", 1));
            } else if (JackarooEngine.current(g.s) == x) {
                // Pass the turn on
                int n = g.s.players.size();
                for (int i = 1; i <= n; i++) {
                    int t = (g.s.turn + i) % n;
                    if (!g.s.players.get(t).left && !g.s.players.get(t).hand.isEmpty()) {
                        g.s.turn = t;
                        break;
                    }
                }
            }
            save(g, "PLAYING".equals(g.status) ? Instant.now().plusSeconds(TURN_SECONDS) : null);
        }
        return view(gameId, me);
    }

    @Transactional
    public Map<String, Object> start(Long gameId, Long me) {
        Row g = lock(gameId);
        if (!"WAITING".equals(g.status)) throw conflict("This game already started.");
        if (g.createdBy != me) throw forbidden("Only the one who opened the table can start.");
        int n = g.s.players.size();
        if (n != 2 && n != 4) throw conflict("Ludo needs 2 or 4 players. Now: " + n + ".");
        if (n == 2 && g.s.players.get(0).seat % 2 == g.s.players.get(1).seat % 2) {
            // 2 players sit opposite each other
            g.s.players.sort(Comparator.comparingInt(p -> p.seat));
            g.s.players.get(0).seat = 0;
            g.s.players.get(1).seat = 2;
        }
        JackarooEngine.start(g.s);
        g.status = "PLAYING";
        save(g, Instant.now().plusSeconds(TURN_SECONDS));
        return view(gameId, me);
    }

    @Transactional
    public Map<String, Object> cancel(Long gameId, Long me) {
        Row g = lock(gameId);
        if (g.createdBy != me && !"WAITING".equals(g.status)) throw forbidden("Only the one who opened the table can close it.");
        if (g.createdBy != me) throw forbidden("Only the one who opened the table can close it.");
        g.status = "CANCELLED";
        g.s.last = "The table was closed.";
        save(g, null);
        return view(gameId, me);
    }

    /* ---------------- play ---------------- */

    @Transactional
    public Map<String, Object> play(Long gameId, Long me, int moveIndex) {
        Row g = lock(gameId);
        requireTurn(g, me);
        List<Move> legal = JackarooEngine.legal(g.s);
        if (legal.isEmpty()) throw conflict("No move is possible. Burn a card.");
        if (moveIndex < 0 || moveIndex >= legal.size()) throw bad("That move isn't possible any more.");
        JackarooEngine.apply(g.s, legal.get(moveIndex));
        afterMove(g);
        return view(gameId, me);
    }

    @Transactional
    public Map<String, Object> burn(Long gameId, Long me, int cardIndex) {
        Row g = lock(gameId);
        requireTurn(g, me);
        if (!JackarooEngine.legal(g.s).isEmpty()) throw conflict("You have a move. Play a card instead.");
        JackarooEngine.burn(g.s, cardIndex);
        afterMove(g);
        return view(gameId, me);
    }

    private void afterMove(Row g) {
        if (g.s.winnerTeam != null) {
            g.status = "FINISHED";
            // Game win EXP for the winning team
            List<Long> winners = g.s.players.stream().filter(p -> p.team == g.s.winnerTeam && !p.left).map(p -> p.userId).toList();
            afterCommit(() -> winners.forEach(u -> exp.add(u, "GAME", com.panchayat.backend.progress.service.ExpService.GAME_WIN)));
            g.s.players.forEach(q -> dailyTasks.track(q.userId, "PLAY_GAME", 1));
        }
        save(g, "PLAYING".equals(g.status) ? Instant.now().plusSeconds(TURN_SECONDS) : null);
    }

    /** Turns that ran out of time: the server plays for them. */
    private void timeouts() {
        try {
            List<Long> due = jdbc.queryForList("select id from room_games where game = 'JACKAROO' and status = 'PLAYING' and turn_ends_at < now() limit 20", Long.class);
            for (Long id : due) {
                try {
                    tx.executeWithoutResult(t -> {
                        Row g = lock(id);
                        if (!"PLAYING".equals(g.status) || g.turnEndsAt == null || g.turnEndsAt.isAfter(Instant.now())) return;
                        List<Move> legal = JackarooEngine.legal(g.s);
                        if (legal.isEmpty()) JackarooEngine.burn(g.s, 0);
                        else JackarooEngine.apply(g.s, legal.get(0));
                        g.s.last = "⏱ " + g.s.last;
                        afterMove(g);
                    });
                } catch (Exception e) {
                    log.warn("Jackaroo timeout for game {} failed: {}", id, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.debug("Jackaroo timer: {}", e.getMessage());
        }
    }

    /* ---------------- reading ---------------- */

    @Transactional(readOnly = true)
    public Map<String, Object> current(Long roomId, Long me) {
        List<Long> open = jdbc.queryForList("select id from room_games where room_id = ? and game = 'JACKAROO' and status in ('WAITING','PLAYING') order by id desc limit 1", Long.class, roomId);
        if (open.isEmpty()) {
            // The game that just ended stays visible for a minute (the winner screen)
            open = jdbc.queryForList("select id from room_games where room_id = ? and game = 'JACKAROO' and status = 'FINISHED' and updated_at > now() - interval '1 minute' order by id desc limit 1", Long.class, roomId);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("game", open.isEmpty() ? null : view(open.get(0), me));
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(Long gameId, Long me) {
        return view(gameId, me);
    }

    /** The table as everyone sees it, plus your hand and legal moves when you're playing. */
    public Map<String, Object> view(Long gameId, Long me) {
        Row g = read(gameId);
        Map<String, Object> v = publicView(g);
        g.s.players.stream().filter(p -> p.userId == me).findFirst().ifPresent(p -> v.put("you", privateView(g, p)));
        return v;
    }

    private Map<String, Object> publicView(Row g) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", String.valueOf(g.id));
        v.put("game", "JACKAROO");
        v.put("roomId", String.valueOf(g.roomId));
        v.put("code", g.code);
        v.put("status", g.status);
        v.put("createdBy", String.valueOf(g.createdBy));
        v.put("version", g.version);
        v.put("turnEndsAt", g.turnEndsAt == null ? null : g.turnEndsAt.toEpochMilli());
        v.put("serverTime", System.currentTimeMillis());
        v.put("turnSeconds", TURN_SECONDS);
        List<Map<String, Object>> ps = new ArrayList<>();
        for (Player p : g.s.players) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("userId", String.valueOf(p.userId));
            m.put("name", p.name);
            m.put("avatarUrl", MediaUrls.link(p.avatar));
            m.put("seat", p.seat);
            m.put("team", p.team);
            m.put("marbles", p.marbles);
            m.put("cards", p.hand.size());
            m.put("left", p.left);
            ps.add(m);
        }
        v.put("players", ps);
        v.put("turnUserId", "PLAYING".equals(g.status) && !g.s.players.isEmpty() ? String.valueOf(JackarooEngine.current(g.s).userId) : null);
        v.put("winnerTeam", g.s.winnerTeam);
        v.put("winners", g.s.winnerTeam == null ? List.of() : g.s.players.stream().filter(p -> p.team == g.s.winnerTeam).map(p -> String.valueOf(p.userId)).toList());
        v.put("last", g.s.last);
        v.put("lastCard", g.s.lastCard);
        v.put("lastMove", g.s.lastMove);
        v.put("round", g.s.round);
        v.put("deckLeft", g.s.deck.size());
        return v;
    }

    private Map<String, Object> privateView(Row g, Player p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seat", p.seat);
        m.put("hand", p.hand);
        boolean myTurn = "PLAYING".equals(g.status) && JackarooEngine.current(g.s) == p;
        m.put("myTurn", myTurn);
        List<Map<String, Object>> legal = new ArrayList<>();
        if (myTurn) {
            List<Move> l = JackarooEngine.legal(g.s);
            for (int i = 0; i < l.size(); i++) {
                Move mv = l.get(i);
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("index", i);
                x.put("card", mv.card());
                x.put("marble", mv.marble());
                x.put("action", mv.action());
                x.put("targetSeat", mv.targetSeat());
                x.put("targetMarble", mv.targetMarble());
                x.put("label", mv.label());
                legal.add(x);
            }
        }
        m.put("legal", legal);
        m.put("mustBurn", myTurn && legal.isEmpty());
        return m;
    }

    /* ---------------- internals ---------------- */

    private static final class Row {
        long id;
        long roomId;
        long createdBy;
        String status;
        String code;
        int version;
        Instant turnEndsAt;
        State s;
    }

    private Row read(Long id) {
        return load(id, false);
    }

    private Row lock(Long id) {
        return load(id, true);
    }

    private Row load(Long id, boolean forUpdate) {
        List<Row> l = jdbc.query("select * from room_games where id = ? and game = 'JACKAROO'" + (forUpdate ? " for update" : ""), (rs, i) -> {
            Row r = new Row();
            r.id = rs.getLong("id");
            r.roomId = rs.getLong("room_id");
            r.createdBy = rs.getLong("created_by");
            r.status = rs.getString("status");
            r.code = rs.getString("code");
            r.version = rs.getInt("version");
            Timestamp t = rs.getTimestamp("turn_ends_at");
            r.turnEndsAt = t == null ? null : t.toInstant();
            r.s = json.readValue(rs.getString("state"), State.class);
            return r;
        }, id);
        if (l.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That game is gone.");
        return l.get(0);
    }

    private void save(Row g, Instant turnEnds) {
        g.version++;
        jdbc.update("update room_games set state = ?::jsonb, status = ?, version = ?, turn_ends_at = ?, updated_at = now() where id = ?",
                json.writeValueAsString(g.s), g.status, g.version, turnEnds == null ? null : Timestamp.from(turnEnds), g.id);
        g.turnEndsAt = turnEnds;
        long id = g.id;
        afterCommit(() -> broadcast(id));
    }

    /** The table to the room, and each player's hand to them. */
    private void broadcast(long id) {
        try {
            Row g = read(id);
            messaging.convertAndSend("/topic/voice-rooms/" + g.roomId + "/game", (Object) publicView(g));
            for (Player p : g.s.players) {
                String email = jdbc.query("select email from users where id = ?", rs -> rs.next() ? rs.getString(1) : null, p.userId);
                if (email == null) continue;
                Map<String, Object> mine = new LinkedHashMap<>(privateView(g, p));
                mine.put("gameId", String.valueOf(id));
                mine.put("version", g.version);
                messaging.convertAndSendToUser(email, "/queue/game", (Object) mine);
            }
        } catch (Exception e) {
            log.debug("Jackaroo broadcast failed: {}", e.getMessage());
        }
    }

    private Player player(Long userId, int seat) {
        Map<String, Object> u = jdbc.queryForMap("select full_name, avatar_url from users where id = ?", userId);
        Player p = new Player();
        p.userId = userId;
        p.name = u.get("full_name") == null ? "Player" : (String) u.get("full_name");
        p.avatar = com.panchayat.backend.admin.media.MediaUrls.protectedLink((String) u.get("avatar_url"));
        p.seat = seat;
        return p;
    }

    private static int firstFree(State s) {
        // Fill opposite seats first, so a 2-player game sits across the board
        for (int seat : new int[]{0, 2, 1, 3}) {
            final int x = seat;
            if (s.players.stream().noneMatch(p -> p.seat == x)) return seat;
        }
        throw conflict("The table is full.");
    }

    private void requireInRoom(Long roomId, Long me) {
        Integer n = jdbc.queryForObject("select count(*) from voice_room_participants where room_id = ? and user_id = ? and status = 'JOINED'", Integer.class, roomId, me);
        if (n == null || n == 0) throw forbidden("Join the room to play.");
    }

    private static void requireTurn(Row g, Long me) {
        if (!"PLAYING".equals(g.status)) throw conflict("The game isn't running.");
        if (JackarooEngine.current(g.s).userId != me) throw conflict("It's not your turn.");
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

    private static ResponseStatusException bad(String m) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, m); }
    private static ResponseStatusException conflict(String m) { return new ResponseStatusException(HttpStatus.CONFLICT, m); }
    private static ResponseStatusException forbidden(String m) { return new ResponseStatusException(HttpStatus.FORBIDDEN, m); }
}
