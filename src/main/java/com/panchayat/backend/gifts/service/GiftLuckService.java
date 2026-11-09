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

    /** Chance of a big win (the top third of the range, up to the max) and of a middle win (the middle third). */
    static final double HIGH_CHANCE = 0.01;
    static final double MIDDLE_CHANCE = 0.025;

    /**
     * A lucky amount from min to max where small wins are the usual: the range is cut in three. About 1 in 100
     * lands in the top third (the max is a rare jackpot), about 2 to 3 in 100 in the middle third, and the
     * rest (about 96 in 100) in the lowest third.
     */
    static long tiered(java.util.Random random, long min, long max) {
        if (max <= min) return min;
        long third = (max - min) / 3;
        double r = random.nextDouble();
        long lo, hi;
        if (r < HIGH_CHANCE) {
            lo = min + 2 * third + 1;
            hi = max;
        } else if (r < HIGH_CHANCE + MIDDLE_CHANCE) {
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
        long lo = Math.max(BONUS_MIN, coins + 1);
        if (lo > BONUS_MAX) return coins + 1 + (long) (random.nextDouble() * (coins / 10 + 1));
        return tiered(random, lo, BONUS_MAX);
    }

    /** Pay the lucky coins to the receiver (same transaction as the gift); returns how many (0 for none). */
    public long bonus(Long senderId, Long receiverId, String giftId, int count, long coins, boolean fromBag, String requestId, String ref) {
        if (fromBag || coins <= 0 || receiverId == null) return 0;
        // Lucky coins can be switched off from the admin panel (they are off by default)
        if (!luckyEnabled()) return 0;
        Triggers t = triggers(giftId);
        long bonus;
        if (t.coinMin() != null && t.coinMax() != null) {
            // The admin's range for this gift: a random amount from min to max for each gift sent
            bonus = 0;
            for (int i = 0; i < count; i++) bonus = Math.addExact(bonus, tiered(random, t.coinMin(), t.coinMax()));
        } else {
            bonus = roll(coins);
        }
        if (bonus <= 0) return 0;
        final long paid = bonus;
        wallet.credit(receiverId, paid, "GIFT_LUCKY_BONUS", ref, "gift:" + requestId + ":lucky", "Lucky coins from a gift");
        afterCommit(() -> {
            try {
                walletPush.pushBalance(receiverId, "GIFT_LUCKY_BONUS", paid);
                sarpanch.send(receiverId, new SarpanchService.Message("Lucky coins!", "🍀 A gift brought you " + String.format("%,d", paid) + " lucky coins.", null, null, null, "REWARD"), "gift-lucky:" + requestId);
            } catch (Exception e) {
                log.debug("Lucky coins notice not sent: {}", e.getMessage());
            }
        });
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
