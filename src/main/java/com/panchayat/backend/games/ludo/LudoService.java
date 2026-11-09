package com.panchayat.backend.games.ludo;

import com.panchayat.backend.admin.media.MediaUrls;
import com.panchayat.backend.games.ludo.LudoEngine.Move;
import com.panchayat.backend.games.ludo.LudoEngine.Player;
import com.panchayat.backend.games.ludo.LudoEngine.State;
import com.panchayat.backend.wallet.service.CoinWalletService;
import com.panchayat.backend.wallet.service.WalletPushService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Ludo at a room's table, refereed by the server (room_games, game = 'LUDO').
 *
 *  - Anyone in the room opens a table with a stake (0, 100, 500, 1000 or 5000
 *    coins) and others sit (2 to 4 players). The opener starts the game: each
 *    player's stake is taken then, and the winner gets the whole pot.
 *  - The dice is rolled on the server. Each step (roll or move) has
 *    TURN_SECONDS; when it runs out the server rolls / moves for you.
 *  - Leaving a running game forfeits your stake; the last one left wins.
 *  - Table chat and emotes: POST /games/{id}/chat, live on /topic/ludo/{id}/chat.
 *  - Everyone sees the table on /topic/ludo/{id} (and the room on /topic/voice-rooms/{roomId}/game).
 */
@Service
public class LudoService {

    private static final Logger log = LoggerFactory.getLogger(LudoService.class);
    public static final int TURN_SECONDS = 20;
    public static final Set<Long> STAKES = Set.of(0L, 100L, 500L, 1000L, 5000L);
    public static final Set<String> EMOTES = Set.of("😂", "😍", "😡", "😭", "🔥", "👏", "😎", "🤬", "🙏", "🎉", "💀", "🫡");

    private final JdbcTemplate jdbc;
    private final SimpMessagingTemplate messaging;
    private final TransactionTemplate tx;
    private final ObjectMapper json = new ObjectMapper();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ludo-timer");
        t.setDaemon(true);
        return t;
    });

    @Autowired @Lazy private CoinWalletService wallet;
    @Autowired @Lazy private WalletPushService walletPush;
    @Autowired @Lazy private com.panchayat.backend.progress.service.ExpService exp;
    @Autowired @Lazy private com.panchayat.backend.progress.tasks.DailyTaskService dailyTasks;

    public LudoService(JdbcTemplate jdbc, SimpMessagingTemplate messaging, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.messaging = messaging;
        this.tx = new TransactionTemplate(txm);
        timer.scheduleWithFixedDelay(this::timeouts, 3, 1, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        timer.shutdownNow();
    }

    /* ---------------- lobby ---------------- */

    @Transactional
    public Map<String, Object> open(Long roomId, Long me, Long stake) {
        requireInRoom(roomId, me);
        long st = stake == null ? 0 : stake;
        if (!STAKES.contains(st)) throw bad("Pick a stake of 0, 100, 500, 1000 or 5000 coins.");
        List<Long> open = jdbc.queryForList("select id from room_games where room_id = ? and game = 'LUDO' and status in ('WAITING','PLAYING')", Long.class, roomId);
        if (!open.isEmpty()) return view(open.get(0), me);
        State s = new State();
        s.stake = st;
        s.players.add(player(me, 3));
        s.last = "Table open. Waiting for players…";
        Long id = null;
        for (int attempt = 0; id == null && attempt < 5; attempt++) {
            try {
                id = jdbc.queryForObject("insert into room_games (room_id, game, created_by, state, code) values (?, 'LUDO', ?, ?::jsonb, ?) returning id",
                        Long.class, roomId, me, json.writeValueAsString(s), com.panchayat.backend.social.invites.InviteService.code(6));
            } catch (org.springframework.dao.DuplicateKeyException clash) {
                // same code twice: try another
            }
        }
        if (id == null) throw conflict("Couldn't open a table. Try again.");
        long fid = id;
        afterCommit(() -> broadcast(fid));
        return view(id, me);
    }

    @Transactional
    public Map<String, Object> sit(Long gameId, Long me) {
        Row g = lock(gameId);
        if (!"WAITING".equals(g.status)) throw conflict("This game already started.");
        requireInRoom(g.roomId, me);
        if (g.s.players.stream().noneMatch(p -> p.userId == me)) {
            if (g.s.players.size() >= 4) throw conflict("The table is full.");
            g.s.players.add(player(me, firstFree(g.s)));
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
            if (g.s.players.isEmpty()) g.status = "CANCELLED";
            else g.s.last = p.get().name + " left the table";
            save(g, null);
        } else if ("PLAYING".equals(g.status)) {
            Player x = p.get();
            boolean wasTurn = LudoEngine.current(g.s) == x;
            x.left = true;
            g.s.last = x.name + " left the game";
            List<Player> still = g.s.players.stream().filter(q -> !q.left).toList();
            if (still.size() <= 1) {
                finish(g, still.isEmpty() ? null : still.get(0));
            } else if (wasTurn) {
                LudoEngine.nextTurn(g.s);
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
        if (n < 2) throw conflict("Ludo needs at least 2 players.");
        // Stakes: everyone pays now, or nobody does
        if (g.s.stake > 0) {
            for (Player p : g.s.players) {
                Long bal = jdbc.query("select balance from coin_wallets where user_id = ?", rs -> rs.next() ? rs.getLong(1) : 0L, p.userId);
                if (bal == null || bal < g.s.stake) throw conflict(p.name + " doesn't have " + g.s.stake + " coins for the stake.");
            }
            for (Player p : g.s.players) {
                wallet.debit(p.userId, g.s.stake, "GAME_STAKE", "ludo:" + g.id, "ludo:" + g.id + ":stake:" + p.userId, "Ludo stake");
                p.paid = true;
                g.s.pot += g.s.stake;
            }
            List<Long> payers = g.s.players.stream().map(p -> p.userId).toList();
            long stake = g.s.stake;
            afterCommit(() -> payers.forEach(u -> walletPush.pushBalance(u, "GAME_STAKE", -stake)));
        }
        // Clockwise turn order by seat, the opener first
        g.s.players.sort(Comparator.comparingInt(p -> p.seat));
        for (int i = 0; i < g.s.players.size(); i++) if (g.s.players.get(i).userId == g.createdBy) g.s.turn = i;
        g.s.phase = "ROLL";
        g.s.last = "Game on! " + LudoEngine.current(g.s).name + " rolls first" + (g.s.pot > 0 ? " · pot " + g.s.pot + " coins" : "");
        g.status = "PLAYING";
        save(g, Instant.now().plusSeconds(TURN_SECONDS));
        return view(gameId, me);
    }

    @Transactional
    public Map<String, Object> cancel(Long gameId, Long me) {
        Row g = lock(gameId);
        if (g.createdBy != me) throw forbidden("Only the one who opened the table can close it.");
        if (!"WAITING".equals(g.status)) throw conflict("A running game can't be closed. Leave it instead.");
        g.status = "CANCELLED";
        g.s.last = "The table was closed.";
        save(g, null);
        return view(gameId, me);
    }

    /* ---------------- play ---------------- */

    @Transactional
    public Map<String, Object> roll(Long gameId, Long me) {
        Row g = lock(gameId);
        requireTurn(g, me);
        if (!"ROLL".equals(g.s.phase)) throw conflict("Move a token first.");
        LudoEngine.roll(g.s);
        save(g, Instant.now().plusSeconds(TURN_SECONDS));
        return view(gameId, me);
    }

    @Transactional
    public Map<String, Object> move(Long gameId, Long me, int token) {
        Row g = lock(gameId);
        requireTurn(g, me);
        if (!"MOVE".equals(g.s.phase)) throw conflict("Roll the dice first.");
        Move m = LudoEngine.legal(g.s).stream().filter(x -> x.token() == token).findFirst()
                .orElseThrow(() -> conflict("That token can't move with this roll."));
        LudoEngine.apply(g.s, m);
        afterMove(g);
        return view(gameId, me);
    }

    private void afterMove(Row g) {
        if (g.s.winner != null) {
            Player w = g.s.players.stream().filter(p -> p.userId == g.s.winner).findFirst().orElse(null);
            finish(g, w);
        }
        save(g, "PLAYING".equals(g.status) ? Instant.now().plusSeconds(TURN_SECONDS) : null);
    }

    /** The game is over: the winner takes the pot, everyone gets the game counted. */
    private void finish(Row g, Player winner) {
        g.status = "FINISHED";
        g.s.winner = winner == null ? null : winner.userId;
        if (winner != null) {
            g.s.last = winner.name + " won" + (g.s.pot > 0 ? " " + g.s.pot + " coins" : "") + "! 🏆";
            if (g.s.pot > 0) {
                wallet.credit(winner.userId, g.s.pot, "GAME_WIN", "ludo:" + g.id, "ludo:" + g.id + ":win", "Ludo win");
                long pot = g.s.pot;
                long uid = winner.userId;
                afterCommit(() -> walletPush.pushBalance(uid, "GAME_WIN", pot));
            }
            long uid = winner.userId;
            afterCommit(() -> exp.add(uid, "GAME", com.panchayat.backend.progress.service.ExpService.GAME_WIN));
        }
        g.s.players.forEach(q -> dailyTasks.track(q.userId, "PLAY_GAME", 1));
    }

    /** Steps that ran out of time: the server rolls / moves for the player. */
    private void timeouts() {
        try {
            List<Long> due = jdbc.queryForList("select id from room_games where game = 'LUDO' and status = 'PLAYING' and turn_ends_at < now() limit 20", Long.class);
            for (Long id : due) {
                try {
                    tx.executeWithoutResult(t -> {
                        Row g = lock(id);
                        if (!"PLAYING".equals(g.status) || g.turnEndsAt == null || g.turnEndsAt.isAfter(Instant.now())) return;
                        if ("ROLL".equals(g.s.phase)) {
                            LudoEngine.roll(g.s);
                            g.s.last = "⏱ " + g.s.last;
                            save(g, Instant.now().plusSeconds(TURN_SECONDS));
                        } else {
                            List<Move> legal = LudoEngine.legal(g.s);
                            if (legal.isEmpty()) LudoEngine.nextTurn(g.s);
                            else LudoEngine.apply(g.s, LudoEngine.bestMove(legal));
                            g.s.last = "⏱ " + g.s.last;
                            afterMove(g);
                        }
                    });
                } catch (Exception e) {
                    log.warn("Ludo timeout for game {} failed: {}", id, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.debug("Ludo timer: {}", e.getMessage());
        }
    }

    /* ---------------- chat ---------------- */

    public Map<String, Object> chat(Long gameId, Long me, String text, String emote) {
        Row g = read(gameId);
        if (!"WAITING".equals(g.status) && !"PLAYING".equals(g.status) && !"FINISHED".equals(g.status)) throw conflict("This table is closed.");
        requireInRoom(g.roomId, me);
        String t = text == null ? "" : text.strip();
        if (t.length() > 120) t = t.substring(0, 120);
        String e = emote != null && EMOTES.contains(emote) ? emote : null;
        if (t.isEmpty() && e == null) throw bad("Say something.");
        long now = System.currentTimeMillis();
        Map<String, Object> u = jdbc.queryForMap("select full_name, avatar_url from users where id = ?", me);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", UUID.randomUUID().toString());
        m.put("userId", String.valueOf(me));
        m.put("name", u.get("full_name"));
        m.put("avatarUrl", MediaUrls.link((String) u.get("avatar_url")));
        m.put("seat", g.s.players.stream().filter(p -> p.userId == me).map(p -> p.seat).findFirst().orElse(null));
        m.put("text", t.isEmpty() ? null : t);
        m.put("emote", e);
        m.put("at", now);
        messaging.convertAndSend("/topic/ludo/" + gameId + "/chat", (Object) m);
        return m;
    }

    /* ---------------- reading ---------------- */

    @Transactional(readOnly = true)
    public Map<String, Object> current(Long roomId, Long me) {
        List<Long> open = jdbc.queryForList("select id from room_games where room_id = ? and game = 'LUDO' and status in ('WAITING','PLAYING') order by id desc limit 1", Long.class, roomId);
        if (open.isEmpty()) {
            open = jdbc.queryForList("select id from room_games where room_id = ? and game = 'LUDO' and status = 'FINISHED' and updated_at > now() - interval '1 minute' order by id desc limit 1", Long.class, roomId);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("game", open.isEmpty() ? null : view(open.get(0), me));
        return out;
    }

    public Map<String, Object> view(Long gameId, Long me) {
        Row g = read(gameId);
        Map<String, Object> v = publicView(g);
        g.s.players.stream().filter(p -> p.userId == me).findFirst().ifPresent(p -> {
            Map<String, Object> you = new LinkedHashMap<>();
            you.put("seat", p.seat);
            boolean myTurn = "PLAYING".equals(g.status) && LudoEngine.current(g.s) == p;
            you.put("myTurn", myTurn);
            you.put("canRoll", myTurn && "ROLL".equals(g.s.phase));
            List<Integer> movable = new ArrayList<>();
            if (myTurn && "MOVE".equals(g.s.phase)) LudoEngine.legal(g.s).forEach(m -> movable.add(m.token()));
            you.put("movable", movable);
            v.put("you", you);
        });
        return v;
    }

    private Map<String, Object> publicView(Row g) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", String.valueOf(g.id));
        v.put("game", "LUDO");
        v.put("roomId", String.valueOf(g.roomId));
        v.put("code", g.code);
        v.put("status", g.status);
        v.put("createdBy", String.valueOf(g.createdBy));
        v.put("version", g.version);
        v.put("turnEndsAt", g.turnEndsAt == null ? null : g.turnEndsAt.toEpochMilli());
        v.put("serverTime", System.currentTimeMillis());
        v.put("turnSeconds", TURN_SECONDS);
        v.put("stake", g.s.stake);
        v.put("pot", g.s.pot);
        List<Map<String, Object>> ps = new ArrayList<>();
        for (Player p : g.s.players) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("userId", String.valueOf(p.userId));
            m.put("name", p.name);
            m.put("avatarUrl", MediaUrls.link(p.avatar));
            m.put("seat", p.seat);
            m.put("tokens", p.tokens);
            m.put("home", Arrays.stream(p.tokens).filter(t -> t == LudoEngine.FINISH).count());
            m.put("left", p.left);
            ps.add(m);
        }
        v.put("players", ps);
        boolean playing = "PLAYING".equals(g.status) && !g.s.players.isEmpty();
        v.put("turnUserId", playing ? String.valueOf(LudoEngine.current(g.s).userId) : null);
        v.put("phase", g.s.phase);
        v.put("dice", g.s.dice);
        v.put("rolls", g.s.rolls);
        v.put("winner", g.s.winner == null ? null : String.valueOf(g.s.winner));
        v.put("last", g.s.last);
        v.put("lastMove", g.s.lastMove);
        return v;
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
        List<Row> l = jdbc.query("select * from room_games where id = ? and game = 'LUDO'" + (forUpdate ? " for update" : ""), (rs, i) -> {
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

    private void broadcast(long id) {
        try {
            Row g = read(id);
            Map<String, Object> v = publicView(g);
            messaging.convertAndSend("/topic/ludo/" + id, (Object) v);
            messaging.convertAndSend("/topic/voice-rooms/" + g.roomId + "/game", (Object) v);
        } catch (Exception e) {
            log.debug("Ludo broadcast failed: {}", e.getMessage());
        }
    }

    private Player player(Long userId, int seat) {
        Map<String, Object> u = jdbc.queryForMap("select full_name, avatar_url from users where id = ?", userId);
        Player p = new Player();
        p.userId = userId;
        p.name = u.get("full_name") == null ? "Player" : (String) u.get("full_name");
        p.avatar = (String) u.get("avatar_url");
        p.seat = seat;
        return p;
    }

    /** Opposite seats first, so two players sit across the board. */
    private static int firstFree(State s) {
        for (int seat : new int[]{3, 1, 0, 2}) {
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
        if (LudoEngine.current(g.s).userId != me) throw conflict("It's not your turn.");
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
