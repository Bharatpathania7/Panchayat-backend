package com.panchayat.backend.gifts.service;

import com.panchayat.backend.sarpanch.SarpanchService;
import com.panchayat.backend.wallet.service.CoinWalletService;
import com.panchayat.backend.wallet.service.WalletPushService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.SecureRandom;
import java.util.List;

/**
 * What a gift gives the person who gets it, besides their share of the coins:
 *
 * Charm: each gift has a fixed charm set in the admin panel (gifts.charm), per gift sent.
 * A gift with no charm set keeps the old rule of 1 charm per 5 coins.
 *
 * Lucky coins: every gift sent with coins also drops a random number of coins on the
 * receiver, between 60,000 and 2,00,000 and always more than what the gift cost. (A gift from a Bag gives none.)
 */
@Service
public class GiftLuckService {

    private static final Logger log = LoggerFactory.getLogger(GiftLuckService.class);
    public static final long BONUS_MIN = 60_000;
    public static final long BONUS_MAX = 200_000;

    private final JdbcTemplate jdbc;
    private final CoinWalletService wallet;
    private final WalletPushService walletPush;
    private final SarpanchService sarpanch;
    private final SecureRandom random = new SecureRandom();

    public GiftLuckService(JdbcTemplate jdbc, CoinWalletService wallet, WalletPushService walletPush, SarpanchService sarpanch) {
        this.jdbc = jdbc;
        this.wallet = wallet;
        this.walletPush = walletPush;
        this.sarpanch = sarpanch;
    }

    /** Whether gifts give the receiver lucky coins right now (admin switch). */
    public boolean luckyEnabled() {
        Boolean on = jdbc.query("select lucky_enabled from gift_settings where id = 1", rs -> rs.next() ? rs.getBoolean(1) : Boolean.FALSE);
        return Boolean.TRUE.equals(on);
    }

    public void setLuckyEnabled(boolean on) {
        jdbc.update("insert into gift_settings (id, lucky_enabled) values (1, ?) on conflict (id) do update set lucky_enabled = excluded.lucky_enabled", on);
    }

    /** The ranges the admin set for a gift: Charm and lucky coins, each min to max (null = not set). */
    record Triggers(Long charmMin, Long charmMax, Long coinMin, Long coinMax, Long fixedCharm) {
    }

    private Triggers triggers(String giftId) {
        List<Triggers> l = jdbc.query("select charm_min, charm_max, coin_min, coin_max, charm from gifts where id = ?",
                (rs, i) -> new Triggers((Long) rs.getObject(1), (Long) rs.getObject(2), (Long) rs.getObject(3), (Long) rs.getObject(4), (Long) rs.getObject(5)), giftId);
        return l.isEmpty() ? new Triggers(null, null, null, null, null) : l.get(0);
    }

    /** Chance of a big win (the top third of the range, up to the max) and of a middle win (the middle third): the defaults. */
    static final double HIGH_CHANCE = 0.02;
    static final double MIDDLE_CHANCE = 0.0;

    /** Everything the admin sets for lucky coins. */
    public record Settings(boolean enabled, double chancePercent, long defaultMin, long defaultMax, double highChance, double middleChance, long minGiftCoins, long dailyCap, int pairDailyLimit) {
    }

    private static final java.time.ZoneId ZONE = java.time.ZoneId.of("Asia/Kolkata");

    public Settings settings() {
        Settings s = jdbc.query("select lucky_enabled, lucky_chance_percent, lucky_default_min, lucky_default_max, lucky_high_chance, lucky_middle_chance, lucky_min_gift_coins, lucky_daily_cap, lucky_pair_daily_limit from gift_settings where id = 1",
                rs -> rs.next() ? new Settings(rs.getBoolean(1), rs.getDouble(2), rs.getLong(3), rs.getLong(4), rs.getDouble(5), rs.getDouble(6), rs.getLong(7), rs.getLong(8), rs.getInt(9)) : null);
        return s != null ? s : new Settings(false, 100, BONUS_MIN, BONUS_MAX, HIGH_CHANCE * 100, MIDDLE_CHANCE * 100, 0, 0, 0);
    }

    /** Saves the settings the admin sent (missing ones stay as they are). Returns the saved settings. */
    public Settings save(java.util.Map<String, Object> in) {
        Settings c = settings();
        boolean enabled = in.get("enabled") instanceof Boolean b ? b : c.enabled();
        double chance = num(in.get("chancePercent"), c.chancePercent());
        long min = (long) num(in.get("defaultMin"), c.defaultMin());
        long max = (long) num(in.get("defaultMax"), c.defaultMax());
        double high = num(in.get("highChance"), c.highChance());
        double mid = num(in.get("middleChance"), c.middleChance());
        long minGift = (long) num(in.get("minGiftCoins"), c.minGiftCoins());
        long cap = (long) num(in.get("dailyCap"), c.dailyCap());
        int pair = (int) num(in.get("pairDailyLimit"), c.pairDailyLimit());
        if (chance < 0 || chance > 100) throw new IllegalArgumentException("The chance must be between 0 and 100.");
        if (min < 0 || max < min) throw new IllegalArgumentException("The most lucky coins can't be less than the least.");
        if (high < 0 || mid < 0 || high + mid > 100) throw new IllegalArgumentException("The big and middle win chances together can't be over 100.");
        if (minGift < 0 || cap < 0 || pair < 0) throw new IllegalArgumentException("Limits can't be negative (0 means no limit).");
        jdbc.update("insert into gift_settings (id, lucky_enabled, lucky_chance_percent, lucky_default_min, lucky_default_max, lucky_high_chance, lucky_middle_chance, lucky_min_gift_coins, lucky_daily_cap, lucky_pair_daily_limit) values (1, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                + " on conflict (id) do update set lucky_enabled = excluded.lucky_enabled, lucky_chance_percent = excluded.lucky_chance_percent, lucky_default_min = excluded.lucky_default_min, lucky_default_max = excluded.lucky_default_max,"
                + " lucky_high_chance = excluded.lucky_high_chance, lucky_middle_chance = excluded.lucky_middle_chance, lucky_min_gift_coins = excluded.lucky_min_gift_coins, lucky_daily_cap = excluded.lucky_daily_cap, lucky_pair_daily_limit = excluded.lucky_pair_daily_limit",
                enabled, chance, min, max, high, mid, minGift, cap, pair);
        return settings();
    }

    private static double num(Object v, double fallback) {
        if (v == null) return fallback;
        if (v instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Numbers only, please.");
        }
    }

    /** Recent payouts and today's totals for the admin panel. */
    public java.util.Map<String, Object> report() {
        java.sql.Timestamp start = java.sql.Timestamp.from(java.time.LocalDate.now(ZONE).atStartOfDay(ZONE).toInstant());
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("todayCount", jdbc.queryForObject("select count(*) from gift_lucky_log where created_at >= ?", Long.class, start));
        out.put("todayCoins", jdbc.queryForObject("select coalesce(sum(amount), 0) from gift_lucky_log where created_at >= ?", Long.class, start));
        out.put("recent", jdbc.queryForList("select l.id, l.receiver_id as \"receiverId\", u.full_name as receiver, l.sender_id as \"senderId\", l.gift_id as \"giftId\", l.gift_count as count, l.gift_coins as \"giftCoins\", l.amount, l.created_at as \"createdAt\""
                + " from gift_lucky_log l left join users u on u.id = l.receiver_id order by l.id desc limit 100"));
        return out;
    }

    /**
     * A lucky amount from min to max where small wins are the usual: the range is cut in three. By default 98 in 100
     * land in the lowest third (a different amount every time) and 2 in 100 in the top third (the max is a rare
     * jackpot); the admin can also give the middle third a share.
     */
    static long tiered(java.util.Random random, long min, long max) {
        return tiered(random, min, max, HIGH_CHANCE, MIDDLE_CHANCE);
    }

    static long tiered(java.util.Random random, long min, long max, double highChance, double middleChance) {
        if (max <= min) return min;
        long third = (max - min) / 3;
        double r = random.nextDouble();
        long lo, hi;
        if (r < highChance) {
            lo = min + 2 * third + 1;
            hi = max;
        } else if (r < highChance + middleChance) {
            lo = min + third + 1;
            hi = min + 2 * third;
        } else {
            lo = min;
            hi = min + third;
        }
        if (hi < lo) hi = lo;
        return lo + (long) (random.nextDouble() * (hi - lo + 1));
    }

    /** A whole number from min to max, both included. */
    private long between(long min, long max) {
        if (max <= min) return min;
        return min + (long) (random.nextDouble() * (max - min + 1));
    }

    /**
     * Charm the receiver earns, times the weekend factor: a random number in the gift's Charm range (admin panel)
     * for each gift sent; else the gift's old fixed Charm; else 1 per 5 coins.
     */
    public long charm(String giftId, int count, long coins, int weekendFactor) {
        Triggers t = triggers(giftId);
        long base;
        if (t.charmMin() != null && t.charmMax() != null) {
            base = 0;
            for (int i = 0; i < count; i++) base = Math.addExact(base, between(t.charmMin(), t.charmMax()));
        } else if (t.fixedCharm() != null) {
            base = Math.multiplyExact(t.fixedCharm(), (long) count);
        } else {
            base = coins / 5;
        }
        return base * weekendFactor;
    }

    /** A random bonus above the gift's price, 60,000 to 2,00,000 (a gift dearer than that gets a little more than its price). */
    long roll(long coins) {
        return roll(coins, settings());
    }

    long roll(long coins, Settings st) {
        long lo = Math.max(st.defaultMin(), coins + 1);
        if (lo > st.defaultMax()) return coins + 1 + (long) (random.nextDouble() * (coins / 10 + 1));
        return tiered(random, lo, st.defaultMax(), st.highChance() / 100.0, st.middleChance() / 100.0);
    }

    /** Pay the lucky coins to the receiver (same transaction as the gift); returns how many (0 for none). */
    public long bonus(Long senderId, Long receiverId, String giftId, int count, long coins, boolean fromBag, String requestId, String ref) {
        if (fromBag || coins <= 0 || receiverId == null) return 0;
        // Gifts to yourself never pay lucky coins
        if (receiverId.equals(senderId)) return 0;
        Settings st = settings();
        // Lucky coins can be switched off from the admin panel (they are off by default)
        if (!st.enabled()) return 0;
        // Small gifts can be left out, and not every gift has to be lucky
        if (coins < st.minGiftCoins()) return 0;
        if (st.chancePercent() < 100 && random.nextDouble() * 100 >= st.chancePercent()) return 0;
        Triggers t = triggers(giftId);
        long bonus;
        if (t.coinMin() != null && t.coinMax() != null) {
            // The admin's range for this gift: a random amount from min to max for each gift sent
            bonus = 0;
            for (int i = 0; i < count; i++) bonus = Math.addExact(bonus, tiered(random, t.coinMin(), t.coinMax(), st.highChance() / 100.0, st.middleChance() / 100.0));
        } else {
            bonus = roll(coins, st);
        }
        // Daily limits (India time), checked while holding a lock on the receiver so two gifts at once can't both slip through
        jdbc.query("select pg_advisory_xact_lock(?)", rs -> null, 770_000_000_000L + receiverId);
        java.sql.Timestamp start = java.sql.Timestamp.from(java.time.LocalDate.now(ZONE).atStartOfDay(ZONE).toInstant());
        if (st.pairDailyLimit() > 0 && senderId != null) {
            Long fromSender = jdbc.queryForObject("select count(*) from gift_lucky_log where receiver_id = ? and sender_id = ? and created_at >= ?", Long.class, receiverId, senderId, start);
            if (fromSender != null && fromSender >= st.pairDailyLimit()) return 0;
        }
        if (st.dailyCap() > 0) {
            Long got = jdbc.queryForObject("select coalesce(sum(amount), 0) from gift_lucky_log where receiver_id = ? and created_at >= ?", Long.class, receiverId, start);
            long left = st.dailyCap() - (got == null ? 0 : got);
            if (left <= 0) return 0;
            bonus = Math.min(bonus, left);
        }
        if (bonus <= 0) return 0;
        final long paid = bonus;
        wallet.credit(receiverId, paid, "GIFT_LUCKY_BONUS", ref, "gift:" + requestId + ":lucky", "Lucky coins from a gift");
        jdbc.update("insert into gift_lucky_log (receiver_id, sender_id, gift_id, gift_count, gift_coins, amount, request_id) values (?, ?, ?, ?, ?, ?, ?) on conflict do nothing", receiverId, senderId, giftId, count, coins, paid, requestId);
        afterCommit(() -> {
            try {
                walletPush.pushBalance(receiverId, "GIFT_LUCKY_BONUS", paid);
            } catch (Exception e) {
                log.debug("Lucky coins balance push failed: {}", e.getMessage());
            }
        });
        // One message in the receiver's Sarpanch chat for every lucky payout, saved in its own transaction
        sarpanch.sendSafely(receiverId, new SarpanchService.Message("Lucky coins!", "A gift brought you " + String.format("%,d", paid) + " lucky coins.", null, null, null, "REWARD"), "gift-lucky:" + requestId);
        return bonus;
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
