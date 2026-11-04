package com.panchayat.backend.progress.tasks;

import com.panchayat.backend.progress.service.ExpService;
import com.panchayat.backend.wallet.service.CoinWalletService;
import com.panchayat.backend.wallet.service.WalletPushService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * Daily tasks: the list comes from the admin panel (daily_tasks); progress is per
 * person per day (India time). Progress is only counted by the server, from real
 * events (gifts, voice minutes, games, invites, messages, moments). Claiming
 * credits the coins once per task per day (idempotency key), plus task EXP.
 */
@Service
public class DailyTaskService {

    private static final Logger log = LoggerFactory.getLogger(DailyTaskService.class);
    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    public static final Set<String> KINDS = Set.of("CHECK_IN", "VOICE_MINUTES", "SEND_GIFT", "PLAY_GAME", "INVITE_FRIEND", "SEND_MESSAGE", "POST_MOMENT");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate txNew;

    @Autowired @Lazy private CoinWalletService wallet;
    @Autowired @Lazy private WalletPushService walletPush;
    @Autowired @Lazy private ExpService exp;

    public DailyTaskService(JdbcTemplate jdbc, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.txNew = new TransactionTemplate(txm);
        this.txNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    /**
     * Something that counts toward tasks of this kind happened. Runs after the caller
     * commits, in its own transaction, and never throws: a task miss never breaks a gift.
     */
    public void track(Long userId, String kind, int amount) {
        if (userId == null || amount <= 0 || !KINDS.contains(kind)) return;
        Runnable r = () -> {
            try {
                txNew.executeWithoutResult(s -> bump(userId, kind, amount));
            } catch (Exception e) {
                log.debug("Task progress not saved: {}", e.getMessage());
            }
        };
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

    private void bump(Long userId, String kind, int amount) {
        Date day = Date.valueOf(today());
        jdbc.update("insert into user_daily_tasks (user_id, day, task_id, progress)"
                        + " select ?, ?, t.id, least(?, t.goal) from daily_tasks t where t.active and t.kind = ?"
                        + " on conflict (user_id, day, task_id) do update set progress = least(user_daily_tasks.progress + excluded.progress,"
                        + " (select goal from daily_tasks d where d.id = user_daily_tasks.task_id))",
                userId, day, amount, kind);
    }

    /** Today's tasks with your progress. Opening the list counts as checking in. */
    @Transactional
    public Map<String, Object> list(Long userId) {
        bump(userId, "CHECK_IN", 1);
        Date day = Date.valueOf(today());
        List<Map<String, Object>> tasks = new ArrayList<>();
        jdbc.query("select t.id, t.title, t.kind, t.goal, t.reward_coins, t.reward_exp, coalesce(u.progress, 0) as progress, u.claimed_at"
                        + " from daily_tasks t left join user_daily_tasks u on u.task_id = t.id and u.user_id = ? and u.day = ?"
                        + " where t.active order by t.sort_order, t.id",
                rs -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", String.valueOf(rs.getLong("id")));
                    m.put("title", rs.getString("title"));
                    m.put("kind", rs.getString("kind"));
                    m.put("goal", rs.getInt("goal"));
                    m.put("progress", Math.min(rs.getInt("progress"), rs.getInt("goal")));
                    m.put("rewardCoins", rs.getLong("reward_coins"));
                    m.put("rewardExp", rs.getInt("reward_exp"));
                    m.put("claimed", rs.getTimestamp("claimed_at") != null);
                    m.put("done", rs.getInt("progress") >= rs.getInt("goal"));
                    tasks.add(m);
                }, userId, day);
        long claimable = tasks.stream().filter(t -> Boolean.TRUE.equals(t.get("done")) && !Boolean.TRUE.equals(t.get("claimed"))).count();
        return Map.of("day", today().toString(), "tasks", tasks, "claimable", claimable);
    }

    /** Claim a finished task: coins once per day (the server checks progress and the reward). */
    @Transactional
    public Map<String, Object> claim(Long userId, Long taskId) {
        Date day = Date.valueOf(today());
        List<Map<String, Object>> t = jdbc.queryForList("select id, title, goal, reward_coins, reward_exp, active from daily_tasks where id = ?", taskId);
        if (t.isEmpty() || !Boolean.TRUE.equals(t.get(0).get("active"))) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That task doesn't exist.");
        List<Map<String, Object>> row = jdbc.queryForList("select progress, claimed_at from user_daily_tasks where user_id = ? and day = ? and task_id = ? for update", userId, day, taskId);
        int goal = ((Number) t.get(0).get("goal")).intValue();
        if (row.isEmpty() || ((Number) row.get(0).get("progress")).intValue() < goal) throw new ResponseStatusException(HttpStatus.CONFLICT, "Finish the task first.");
        if (row.get(0).get("claimed_at") != null) throw new ResponseStatusException(HttpStatus.CONFLICT, "You already claimed this today.");
        jdbc.update("update user_daily_tasks set claimed_at = now() where user_id = ? and day = ? and task_id = ?", userId, day, taskId);
        long coins = ((Number) t.get(0).get("reward_coins")).longValue();
        int expAmount = ((Number) t.get(0).get("reward_exp")).intValue();
        Long balance = null;
        if (coins > 0) {
            var tx = wallet.credit(userId, coins, "DAILY_TASK", "task:" + taskId, "task:" + userId + ":" + day + ":" + taskId, "Daily task: " + t.get(0).get("title"));
            balance = tx.getBalanceAfter();
        }
        final long pushCoins = coins;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    if (expAmount > 0) exp.add(userId, "TASK", expAmount);
                    if (pushCoins > 0) walletPush.pushBalance(userId, "DAILY_TASK", pushCoins);
                } catch (Exception e) {
                    log.debug("Task reward follow-up failed: {}", e.getMessage());
                }
            }
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", String.valueOf(taskId));
        out.put("coins", coins);
        out.put("exp", expAmount);
        out.put("balance", balance);
        return out;
    }

    /* ---------- admin ---------- */

    public List<Map<String, Object>> all() {
        return jdbc.queryForList("select id::text as id, title, kind, goal, reward_coins as \"rewardCoins\", reward_exp as \"rewardExp\", active, sort_order as \"sortOrder\","
                + " (select count(*) from user_daily_tasks u where u.task_id = t.id and u.day = ? and u.claimed_at is not null) as \"claimedToday\""
                + " from daily_tasks t order by sort_order, id", Date.valueOf(today()));
    }

    @Transactional
    public Map<String, Object> save(Long id, Map<String, Object> b) {
        String title = b.get("title") == null ? "" : String.valueOf(b.get("title")).strip();
        String kind = b.get("kind") == null ? "" : String.valueOf(b.get("kind")).toUpperCase(Locale.ROOT);
        if (title.isEmpty() || title.length() > 120) throw bad("Give the task a title (up to 120 letters).");
        if (!KINDS.contains(kind)) throw bad("Unknown task type.");
        int goal = intOf(b.get("goal"), 1);
        long coins = longOf(b.get("rewardCoins"), 0);
        int expv = intOf(b.get("rewardExp"), 50);
        int sort = intOf(b.get("sortOrder"), 0);
        boolean active = !Boolean.FALSE.equals(b.get("active"));
        if (goal < 1 || goal > 10000) throw bad("Goal must be 1 to 10000.");
        if (coins < 0 || coins > 1_000_000) throw bad("Coins must be 0 to 1,000,000.");
        if (expv < 0 || expv > 10000) throw bad("EXP must be 0 to 10000.");
        if (id == null) {
            id = jdbc.queryForObject("insert into daily_tasks (title, kind, goal, reward_coins, reward_exp, active, sort_order) values (?, ?, ?, ?, ?, ?, ?) returning id",
                    Long.class, title, kind, goal, coins, expv, active, sort);
        } else {
            int n = jdbc.update("update daily_tasks set title = ?, kind = ?, goal = ?, reward_coins = ?, reward_exp = ?, active = ?, sort_order = ?, updated_at = now() where id = ?",
                    title, kind, goal, coins, expv, active, sort, id);
            if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That task doesn't exist.");
        }
        String fid = String.valueOf(id);
        return all().stream().filter(m -> fid.equals(m.get("id"))).findFirst().orElseThrow();
    }

    @Transactional
    public void delete(Long id) {
        if (jdbc.update("delete from daily_tasks where id = ?", id) == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That task doesn't exist.");
    }

    private static int intOf(Object v, int d) {
        if (v == null || String.valueOf(v).isBlank()) return d;
        try {
            return new java.math.BigDecimal(String.valueOf(v)).intValueExact();
        } catch (Exception e) {
            throw bad("Numbers must be whole numbers.");
        }
    }

    private static long longOf(Object v, long d) {
        if (v == null || String.valueOf(v).isBlank()) return d;
        try {
            return new java.math.BigDecimal(String.valueOf(v)).longValueExact();
        } catch (Exception e) {
            throw bad("Numbers must be whole numbers.");
        }
    }

    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }
}
