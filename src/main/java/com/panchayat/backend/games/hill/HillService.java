package com.panchayat.backend.games.hill;

import com.panchayat.backend.games.GameSwitches;
import com.panchayat.backend.wallet.service.CoinWalletService;
import com.panchayat.backend.wallet.service.WalletPushService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pahadi Daud: a solo hill-climb game. The phone plays it; the server only decides how many of the coins collected are paid.
 * Coins sit on the road every COIN_GAP metres and go 10, 5, 10, 5, ... (the admin sets the two amounts). A run is paid only for coins
 * that could really be on the road up to the distance reached, within the time that took, and within the run and daily caps.
 * The coins go straight to the wallet (the "bank").
 */
@Service
public class HillService {

    public static final String GAME = "HILL";
    public static final int COIN_GAP = 40, FIRST_COIN = 30;
    /** Faster than this (metres per second) is not possible in the game. */
    static final double MAX_SPEED = 38;
    static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    private final JdbcTemplate jdbc;
    private final GameSwitches switches;
    @Autowired @Lazy private CoinWalletService wallet;
    @Autowired @Lazy private WalletPushService walletPush;

    public HillService(JdbcTemplate jdbc, GameSwitches switches) {
        this.jdbc = jdbc;
        this.switches = switches;
    }

    public record Settings(long dailyCap, long runCap, int coinBig, int coinSmall) {
    }

    public Settings settings() {
        Settings s = jdbc.query("select daily_cap, run_cap, coin_big, coin_small from hill_settings where id = 1", rs -> rs.next() ? new Settings(rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getInt(4)) : null);
        return s != null ? s : new Settings(5000, 1500, 10, 5);
    }

    public Settings save(Long dailyCap, Long runCap, Integer big, Integer small) {
        Settings c = settings();
        long d = dailyCap == null ? c.dailyCap() : dailyCap, r = runCap == null ? c.runCap() : runCap;
        int b = big == null ? c.coinBig() : big, sm = small == null ? c.coinSmall() : small;
        if (d < 0 || d > 10_000_000 || r < 0 || r > 1_000_000 || b < 0 || b > 1000 || sm < 0 || sm > 1000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Those numbers are out of range.");
        jdbc.update("update hill_settings set daily_cap = ?, run_cap = ?, coin_big = ?, coin_small = ?, updated_at = now() where id = 1", d, r, b, sm);
        return settings();
    }

    /** Coins on the road up to this distance (metres): the first at FIRST_COIN, then one every COIN_GAP, worth big, small, big, small... */
    public static long coinsUpTo(int distance, Settings s) {
        if (distance < FIRST_COIN) return 0;
        long n = (distance - FIRST_COIN) / COIN_GAP + 1;
        long pairs = n / 2;
        return pairs * (s.coinBig() + s.coinSmall()) + (n % 2 == 1 ? s.coinBig() : 0);
    }

    private long paidToday(Long userId) {
        String day = LocalDate.now(ZONE).toString();
        Long v = jdbc.queryForObject("select coalesce(sum(coins), 0) from hill_runs where user_id = ? and finished_at >= ?::date and finished_at < (?::date + 1)", Long.class, userId, day, day);
        return v == null ? 0 : v;
    }

    public Map<String, Object> info(Long userId) {
        Settings s = settings();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", switches.enabled(GAME));
        m.put("dailyCap", s.dailyCap());
        m.put("dailyLeft", Math.max(0, s.dailyCap() - paidToday(userId)));
        m.put("coinBig", s.coinBig());
        m.put("coinSmall", s.coinSmall());
        m.put("coinGap", COIN_GAP);
        m.put("firstCoin", FIRST_COIN);
        Integer best = jdbc.queryForObject("select coalesce(max(distance), 0) from hill_runs where user_id = ?", Integer.class, userId);
        m.put("best", best == null ? 0 : best);
        return m;
    }

    @Transactional
    public Map<String, Object> start(Long userId) {
        switches.require(GAME);
        // an old run that was never finished is just dropped (nothing was paid for it)
        jdbc.update("delete from hill_runs where user_id = ? and finished_at is null", userId);
        Long id = jdbc.queryForObject("insert into hill_runs (user_id) values (?) returning id", Long.class, userId);
        Map<String, Object> m = info(userId);
        m.put("runId", String.valueOf(id));
        return m;
    }

    @Transactional
    public Map<String, Object> finish(Long userId, Long runId, int distance, long coins) {
        switches.require(GAME);
        Map<String, Object> run = jdbc.queryForList("select id, extract(epoch from (now() - started_at)) as secs from hill_runs where id = ? and user_id = ? and finished_at is null for update", runId, userId).stream().findFirst().orElse(null);
        if (run == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "This run is already counted.");
        double secs = ((Number) run.get("secs")).doubleValue();
        Settings s = settings();
        int d = Math.max(0, Math.min(distance, 100_000));
        // what the road could really have held up to there, and what the time allows
        int reachable = (int) Math.min(d, secs * MAX_SPEED + 20);
        long possible = coinsUpTo(reachable, s);
        long pay = Math.max(0, Math.min(Math.min(coins, possible), s.runCap()));
        long left = Math.max(0, s.dailyCap() - paidToday(userId));
        pay = Math.min(pay, left);
        jdbc.update("update hill_runs set finished_at = now(), distance = ?, coins = ? where id = ?", reachable, pay, runId);
        long balance = 0;
        if (pay > 0) {
            var tx = wallet.credit(userId, pay, "HILL_COINS", "hill:" + runId, "hill:" + runId, "Pahadi Daud coins");
            balance = tx.getBalanceAfter();
            long amount = pay;
            after(() -> walletPush.pushBalance(userId, "HILL_COINS", amount));
        }
        Map<String, Object> out = info(userId);
        out.put("paid", pay);
        out.put("balance", balance);
        out.put("distance", reachable);
        return out;
    }

    private static void after(Runnable r) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    r.run();
                }
            });
        } else {
            r.run();
        }
    }

    public Map<String, Object> stats() {
        String day = LocalDate.now(ZONE).toString();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runsToday", jdbc.queryForObject("select count(*) from hill_runs where finished_at >= ?::date and finished_at < (?::date + 1)", Long.class, day, day));
        m.put("playersToday", jdbc.queryForObject("select count(distinct user_id) from hill_runs where finished_at >= ?::date and finished_at < (?::date + 1)", Long.class, day, day));
        m.put("coinsToday", jdbc.queryForObject("select coalesce(sum(coins), 0) from hill_runs where finished_at >= ?::date and finished_at < (?::date + 1)", Long.class, day, day));
        m.put("coinsTotal", jdbc.queryForObject("select coalesce(sum(coins), 0) from hill_runs", Long.class));
        return m;
    }
}
