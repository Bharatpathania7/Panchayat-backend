package com.panchayat.backend.progress.service;

import com.panchayat.backend.rooms.service.RoomStateService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Active Level on the server (same rules as the app, lib/app/progress.ts):
 *
 *   GAME      20 EXP per game won                       cap 400 a day
 *   VOICE     on mic 4/min, listening 1/min; half after
 *             10 min in the room, a quarter after 30    cap 120 a day
 *   TASK      50 EXP per daily task claimed             cap 250 a day
 *   FRIEND    10 EXP per new friend                     cap 50 a day
 *   PURCHASE  coin packs: 10,000+ coins → 5,000–10,000
 *             EXP at once, smaller packs coins/10        no cap
 *
 * Level n → n+1 needs 200 + 12·n² EXP (level 1 at 0). A level-up is pushed to
 * the person on /user/queue/notify ({type: LEVEL_UP, level}).
 */
@Service
public class ExpService {
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private com.panchayat.backend.progress.tasks.DailyTaskService dailyTasks;


    private static final Logger log = LoggerFactory.getLogger(ExpService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    public static final Map<String, Integer> CAPS = Map.of("GAME", 400, "VOICE", 120, "TASK", 250, "FRIEND", 50, "PURCHASE", Integer.MAX_VALUE);
    public static final int GAME_WIN = 20;
    public static final int TASK = 50;
    public static final int FRIEND = 10;

    private final JdbcTemplate jdbc;
    private final SimpMessagingTemplate messaging;
    private final RoomStateService roomState;
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "exp-voice-ticker");
        t.setDaemon(true);
        return t;
    });
    /** Fractional voice EXP carried between minutes (decay makes 0.5 and 0.25). */
    private final Map<Long, Double> voiceCarry = new HashMap<>();

    public ExpService(JdbcTemplate jdbc, SimpMessagingTemplate messaging, RoomStateService roomState) {
        this.jdbc = jdbc;
        this.messaging = messaging;
        this.roomState = roomState;
        ticker.scheduleAtFixedRate(this::voiceMinute, 60, 60, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        ticker.shutdownNow();
    }

    public static long toNext(int level) {
        return 200L + 12L * level * level;
    }

    /** Level, EXP into the level and EXP needed for the next, from total EXP. */
    public static long[] levelOf(long exp) {
        int level = 1;
        long left = exp;
        while (left >= toNext(level) && level < 999) {
            left -= toNext(level);
            level++;
        }
        return new long[]{level, left, toNext(level)};
    }

    /**
     * Adds EXP from a source, up to what's left of today's cap. Returns what was
     * added. Runs in its own transaction so a failure never undoes the action
     * that earned it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int add(Long userId, String source, long amount) {
        if (userId == null || amount <= 0 || !CAPS.containsKey(source)) return 0;
        LocalDate day = LocalDate.now(ZONE);
        jdbc.update("insert into user_exp_daily (user_id, day, source, amount) values (?, ?, ?, 0) on conflict do nothing", userId, day, source);
        Integer used = jdbc.queryForObject("select amount from user_exp_daily where user_id = ? and day = ? and source = ? for update", Integer.class, userId, day, source);
        long room = (long) CAPS.get(source) - (used == null ? 0 : used);
        int got = (int) Math.max(0, Math.min(amount, Math.min(room, Integer.MAX_VALUE / 2)));
        if (got <= 0) return 0;
        jdbc.update("update user_exp_daily set amount = amount + ? where user_id = ? and day = ? and source = ?", got, userId, day, source);
        // One atomic update (a no-key update lock, so it never waits on foreign-key locks held by the caller)
        List<Long> after = jdbc.queryForList("update users set exp = exp + ? where id = ? returning exp", Long.class, got, userId);
        if (after.isEmpty()) return 0;
        long b = after.get(0) - got;
        long lvBefore = levelOf(b)[0], lvAfter = levelOf(b + got)[0];
        if (lvAfter > lvBefore) {
            String email = jdbc.query("select email from users where id = ?", rs -> rs.next() ? rs.getString(1) : null, userId);
            if (email != null) {
                try {
                    messaging.convertAndSendToUser(email, "/queue/notify", (Object) Map.of("type", "LEVEL_UP", "level", lvAfter));
                } catch (Exception ignored) {
                    // a missed notice is fine: the app reads the level again
                }
            }
        }
        return got;
    }

    /** Coin packs: big packs give 5,000–10,000 EXP at once, smaller ones coins/10. */
    public static long purchaseExp(long coins) {
        return coins >= 10_000 ? Math.min(10_000, Math.max(5_000, Math.round(coins / 2.0))) : Math.round(coins / 10.0);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> view(Long userId) {
        Long exp = jdbc.queryForObject("select exp from users where id = ?", Long.class, userId);
        long[] lv = levelOf(exp == null ? 0 : exp);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("exp", exp == null ? 0 : exp);
        out.put("level", lv[0]);
        out.put("intoLevel", lv[1]);
        out.put("toNext", lv[2]);
        Map<String, Object> today = new LinkedHashMap<>();
        Map<String, Integer> used = new HashMap<>();
        jdbc.query("select source, amount from user_exp_daily where user_id = ? and day = ?", rs -> {
            used.put(rs.getString(1), rs.getInt(2));
        }, userId, LocalDate.now(ZONE));
        for (String s : List.of("GAME", "VOICE", "TASK", "FRIEND", "PURCHASE")) {
            int cap = CAPS.get(s);
            today.put(s.toLowerCase(Locale.ROOT), Map.of("got", used.getOrDefault(s, 0), "cap", cap == Integer.MAX_VALUE ? -1 : cap));
        }
        out.put("today", today);
        return out;
    }

    /** Every minute: voice EXP for everyone in an open room (more on mic, less the longer they stay). */
    private void voiceMinute() {
        try {
            List<Map<String, Object>> in = jdbc.queryForList(
                    "select p.user_id, p.room_id, extract(epoch from (now() - p.joined_at)) / 60 as mins"
                            + " from voice_room_participants p join voice_rooms v on v.id = p.room_id"
                            + " where p.status = 'JOINED' and v.status = 'ACTIVE' limit 20000");
            Set<Long> seen = new HashSet<>();
            for (Map<String, Object> r : in) {
                long uid = ((Number) r.get("user_id")).longValue();
                if (!seen.add(uid)) continue;
                long rid = ((Number) r.get("room_id")).longValue();
                double mins = r.get("mins") == null ? 0 : ((Number) r.get("mins")).doubleValue();
                boolean onMic = roomState.seatIndexOf(rid, uid) >= 0;
                double base = onMic ? 4 : 1;
                double decay = mins < 10 ? 1 : mins < 30 ? 0.5 : 0.25;
                double total = voiceCarry.getOrDefault(uid, 0.0) + base * decay;
                int whole = (int) Math.floor(total);
                voiceCarry.put(uid, total - whole);
                if (whole > 0) add(uid, "VOICE", whole);
                dailyTasks.track(uid, "VOICE_MINUTES", 1);
            }
            voiceCarry.keySet().retainAll(seen);
        } catch (Exception e) {
            log.debug("Voice EXP tick failed: {}", e.getMessage());
        }
    }
}
