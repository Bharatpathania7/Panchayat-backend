package com.panchayat.backend.dailygift;

import com.panchayat.backend.sarpanch.SarpanchService;
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
 * Two rewards for everyone, both switched on and set from the admin panel (Daily rewards):
 *  1. A daily red packet: every day each person gets N coins from Sarpanch (a message in the Sarpanch chat with "Open red packet").
 *  2. A locked prize: unlocked the moment someone is first seen in the app, same as the welcome gift — no days or active
 *     time required any more (bonus_days / bonus_active_minutes stay in the settings only for the admin's own records).
 *
 * The app pings once a minute while it is open; those pings are the "active time" (still tracked for the admin's numbers).
 */
@Service
public class DailyGiftService {

    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    /** A ping counts for at most this many seconds (the phone pings every 60). */
    private static final int MAX_PING_SECONDS = 90, MIN_PING_GAP_SECONDS = 40;

    private final JdbcTemplate jdbc;
    @Autowired @Lazy private CoinWalletService wallet;
    @Autowired @Lazy private WalletPushService walletPush;
    @Autowired @Lazy private SarpanchService sarpanch;

    public DailyGiftService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Settings(boolean enabled, long dailyCoins, boolean bonusEnabled, long bonusCoins, int bonusDays, int bonusActiveMinutes) {
    }

    public Settings settings() {
        Settings s = jdbc.query("select enabled, daily_coins, bonus_enabled, bonus_coins, bonus_days, bonus_active_minutes from daily_gift_settings where id = 1",
                rs -> rs.next() ? new Settings(rs.getBoolean(1), rs.getLong(2), rs.getBoolean(3), rs.getLong(4), rs.getInt(5), rs.getInt(6)) : null);
        return s != null ? s : new Settings(false, 1000, false, 50_000, 7, 120);
    }

    public Settings save(Boolean enabled, Long dailyCoins, Boolean bonusEnabled, Long bonusCoins, Integer days, Integer minutes) {
        Settings c = settings();
        long dc = dailyCoins == null ? c.dailyCoins() : dailyCoins;
        long bc = bonusCoins == null ? c.bonusCoins() : bonusCoins;
        int d = days == null ? c.bonusDays() : days;
        int m = minutes == null ? c.bonusActiveMinutes() : minutes;
        if (dc < 0 || dc > 10_000_000 || bc < 0 || bc > 100_000_000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Coins must be between 0 and the limit.");
        if (d < 1 || d > 60) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Days must be 1 to 60.");
        if (m < 1 || m > 1000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Active minutes must be 1 to 1000.");
        jdbc.update("update daily_gift_settings set enabled = ?, daily_coins = ?, bonus_enabled = ?, bonus_coins = ?, bonus_days = ?, bonus_active_minutes = ?, updated_at = now() where id = 1",
                enabled == null ? c.enabled() : enabled, dc, bonusEnabled == null ? c.bonusEnabled() : bonusEnabled, bc, d, m);
        return settings();
    }

    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    private static String n(long v) {
        return java.text.NumberFormat.getInstance(new java.util.Locale("en", "IN")).format(v);
    }

    /* ---------------- what the app shows ---------------- */

    public Map<String, Object> state(Long userId) {
        Settings s = settings();
        LocalDate day = today();
        Map<String, Object> out = new LinkedHashMap<>();
        boolean claimed = Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from daily_gift_claims where user_id = ? and day = ?)", Boolean.class, userId, day));
        out.put("dailyEnabled", s.enabled());
        out.put("dailyCoins", s.dailyCoins());
        out.put("dailyClaimed", claimed);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("enabled", s.bonusEnabled());
        b.put("coins", s.bonusCoins());
        b.put("daysRequired", s.bonusDays());
        b.put("minutesRequired", s.bonusActiveMinutes());
        Map<String, Object> p = jdbc.queryForList("select days_done, unlocked_at is not null as unlocked, claimed_at is not null as claimed, coins from locked_prize where user_id = ?", userId).stream().findFirst().orElse(null);
        int done = p == null ? 0 : ((Number) p.get("days_done")).intValue();
        b.put("daysDone", done);
        b.put("unlocked", p != null && Boolean.TRUE.equals(p.get("unlocked")));
        b.put("claimed", p != null && Boolean.TRUE.equals(p.get("claimed")));
        if (p != null && p.get("coins") != null) b.put("coins", ((Number) p.get("coins")).longValue());
        Integer secs = jdbc.query("select seconds from daily_activity where user_id = ? and day = ?", rs -> rs.next() ? rs.getInt(1) : 0, userId, day);
        b.put("todayMinutes", (secs == null ? 0 : secs) / 60);
        out.put("bonus", b);
        return out;
    }

    /* ---------------- the once-a-minute ping ---------------- */

    @Transactional
    public Map<String, Object> ping(Long userId) {
        Settings s = settings();
        if (!s.enabled() && !s.bonusEnabled()) return state(userId);
        if (s.bonusEnabled()) grantBonusOnEntry(userId, s);
        LocalDate day = today();
        // a new day: the first ping creates the row (and tells Sarpanch to send the day's messages)
        int created = jdbc.update("insert into daily_activity (user_id, day, seconds, last_ping) values (?, ?, 0, now()) on conflict do nothing", userId, day);
        if (created == 1) dayStarted(userId, s, day);
        else {
            // add the time since the last ping, but never more than a normal ping's worth, and not for pings that come too fast
            jdbc.update("update daily_activity set seconds = seconds + least(?, greatest(0, extract(epoch from (now() - last_ping))::int)), last_ping = now() "
                    + "where user_id = ? and day = ? and last_ping <= now() - (? * interval '1 second')", MAX_PING_SECONDS, userId, day, MIN_PING_GAP_SECONDS);
        }
        if (s.bonusEnabled()) checkToday(userId, s, day);
        return state(userId);
    }

    /** The first time this person opens the app today: the red packet message, and the locked-prize reminder. */
    private void dayStarted(Long userId, Settings s, LocalDate day) {
        if (s.enabled() && s.dailyCoins() > 0) {
            sarpanch.sendSafely(userId, new SarpanchService.Message("Aaj ka daily red packet 🧧",
                    "Namaste! Panchayat ki taraf se aaj ke " + n(s.dailyCoins()) + " coins aapka intezaar kar rahe hain. Red packet kholo aur coins le lo.",
                    null, "Open red packet", "/daily-gift", "RED_PACKET"), "daily-gift:" + day + ":" + userId);
        }
    }

    /** The locked prize unlocks the moment someone is first seen (no days or active-time grind any more). Runs once per person. */
    private void grantBonusOnEntry(Long userId, Settings s) {
        int inserted = jdbc.update("insert into locked_prize (user_id, days_done, coins, unlocked_at) values (?, ?, ?, now()) on conflict (user_id) do nothing", userId, s.bonusDays(), s.bonusCoins());
        if (inserted == 1) {
            sarpanch.sendSafely(userId, new SarpanchService.Message("Locked prize khul gaya! 🎉🔓",
                    "Namaste! Panchayat ki taraf se aapka " + n(s.bonusCoins()) + " coins ka locked prize abhi khula hai. Abhi le lo!",
                    null, "Open prize", "/daily-gift", "RED_PACKET"), "locked-open:" + userId);
        }
    }

    /** Has today's active time reached the target? Then today counts, once. */
    private void checkToday(Long userId, Settings s, LocalDate day) {
        int counted = jdbc.update("update daily_activity set counted = true where user_id = ? and day = ? and not counted and seconds >= ?", userId, day, s.bonusActiveMinutes() * 60);
        if (counted == 0) return;
        jdbc.update("insert into locked_prize (user_id, days_done) values (?, 0) on conflict do nothing", userId);
        Map<String, Object> p = jdbc.queryForMap("update locked_prize set days_done = days_done + 1 where user_id = ? and unlocked_at is null returning days_done, 1 as ok", userId);
        int done = ((Number) p.get("days_done")).intValue();
        if (done >= s.bonusDays()) {
            jdbc.update("update locked_prize set unlocked_at = now(), coins = ? where user_id = ? and unlocked_at is null", s.bonusCoins(), userId);
            sarpanch.sendSafely(userId, new SarpanchService.Message("Locked prize khul gaya! 🎉🔓",
                    "Shabaash! " + s.bonusDays() + " din poore hue. Aapka " + n(s.bonusCoins()) + " coins ka locked prize ab khul chuka hai. Abhi le lo!",
                    null, "Open prize", "/daily-gift", "RED_PACKET"), "locked-open:" + userId);
        } else {
            sarpanch.sendSafely(userId, new SarpanchService.Message("Aaj ka task poora ✅",
                    "Aaj ka target poora hua! " + done + " din ho gaye, ab " + (s.bonusDays() - done) + " din aur. Locked prize bas thoda door hai 🔒",
                    null, "Dekho", "/daily-gift", "REWARD"), "locked-day:" + day + ":" + userId);
        }
    }

    /* ---------------- claiming ---------------- */

    @Transactional
    public Map<String, Object> claimDaily(Long userId) {
        Settings s = settings();
        if (!s.enabled() || s.dailyCoins() <= 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Daily red packet abhi band hai.");
        LocalDate day = today();
        int n = jdbc.update("insert into daily_gift_claims (user_id, day, coins) values (?, ?, ?) on conflict do nothing", userId, day, s.dailyCoins());
        if (n == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Aaj ka red packet aap pehle hi khol chuke ho.");
        var tx = wallet.credit(userId, s.dailyCoins(), "DAILY_GIFT", "daily:" + day + ":" + userId, "daily:" + day + ":" + userId, "Sarpanch's daily red packet");
        long coins = s.dailyCoins();
        after(() -> walletPush.pushBalance(userId, "DAILY_GIFT", coins));
        return Map.of("amount", coins, "balance", tx.getBalanceAfter());
    }

    @Transactional
    public Map<String, Object> claimBonus(Long userId) {
        Map<String, Object> p = jdbc.queryForList("select coins from locked_prize where user_id = ? and unlocked_at is not null and claimed_at is null for update", userId).stream().findFirst().orElse(null);
        if (p == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Locked prize abhi khula nahi hai.");
        long coins = ((Number) p.get("coins")).longValue();
        jdbc.update("update locked_prize set claimed_at = now() where user_id = ?", userId);
        var tx = wallet.credit(userId, coins, "LOCKED_PRIZE", "locked:" + userId, "locked:" + userId, "Locked prize");
        after(() -> walletPush.pushBalance(userId, "LOCKED_PRIZE", coins));
        return Map.of("amount", coins, "balance", tx.getBalanceAfter());
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

    /* ---------------- numbers for the admin ---------------- */

    public Map<String, Object> stats() {
        LocalDate day = today();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("claimedToday", jdbc.queryForObject("select count(*) from daily_gift_claims where day = ?", Long.class, day));
        m.put("coinsToday", jdbc.queryForObject("select coalesce(sum(coins), 0) from daily_gift_claims where day = ?", Long.class, day));
        m.put("claimedTotal", jdbc.queryForObject("select count(*) from daily_gift_claims", Long.class));
        m.put("coinsTotal", jdbc.queryForObject("select coalesce(sum(coins), 0) from daily_gift_claims", Long.class));
        m.put("activeToday", jdbc.queryForObject("select count(*) from daily_activity where day = ?", Long.class, day));
        m.put("countedToday", jdbc.queryForObject("select count(*) from daily_activity where day = ? and counted", Long.class, day));
        m.put("inProgress", jdbc.queryForObject("select count(*) from locked_prize where unlocked_at is null", Long.class));
        m.put("unlocked", jdbc.queryForObject("select count(*) from locked_prize where unlocked_at is not null", Long.class));
        m.put("prizeClaimed", jdbc.queryForObject("select count(*) from locked_prize where claimed_at is not null", Long.class));
        m.put("prizeCoinsPaid", jdbc.queryForObject("select coalesce(sum(coins), 0) from locked_prize where claimed_at is not null", Long.class));
        return m;
    }
}
