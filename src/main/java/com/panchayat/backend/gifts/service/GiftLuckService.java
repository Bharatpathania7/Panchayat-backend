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

/**
 * What a gift gives the person who gets it, besides their share of the coins:
 *
 * Charm: each gift has a fixed charm set in the admin panel (gifts.charm), per gift sent.
 * A gift with no charm set keeps the old rule of 1 charm per 5 coins.
 *
 * Lucky coins: every gift sent with coins also drops a random number of coins on the
 * receiver, between 80,000 and 2,00,000 and always more than what the gift cost. (A gift
 * from a Bag, or a gift to yourself, gives none.)
 */
@Service
public class GiftLuckService {

    private static final Logger log = LoggerFactory.getLogger(GiftLuckService.class);
    public static final long BONUS_MIN = 80_000;
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

    /** Charm the receiver earns: the gift's fixed charm times how many, else 1 per 5 coins; times the weekend factor. */
    public long charm(String giftId, int count, long coins, int weekendFactor) {
        Long fixed = jdbc.query("select charm from gifts where id = ?", rs -> {
            if (!rs.next()) return null;
            long v = rs.getLong(1);
            return rs.wasNull() ? null : v;
        }, giftId);
        long base = fixed != null ? Math.multiplyExact(fixed, (long) count) : coins / 5;
        return base * weekendFactor;
    }

    /** A random bonus above the gift's price, 80,000 to 2,00,000 (a gift dearer than that gets a little more than its price). */
    long roll(long coins) {
        long lo = Math.max(BONUS_MIN, coins + 1);
        if (lo > BONUS_MAX) return coins + 1 + (long) (random.nextDouble() * (coins / 10 + 1));
        return lo + (long) (random.nextDouble() * (BONUS_MAX - lo + 1));
    }

    /** Pay the lucky coins to the receiver (same transaction as the gift); returns how many (0 for none). */
    public long bonus(Long senderId, Long receiverId, long coins, boolean fromBag, String requestId, String ref) {
        if (fromBag || coins <= 0 || receiverId == null || receiverId.equals(senderId)) return 0;
        long bonus = roll(coins);
        wallet.credit(receiverId, bonus, "GIFT_LUCKY_BONUS", ref, "gift:" + requestId + ":lucky", "Lucky coins from a gift");
        afterCommit(() -> {
            try {
                walletPush.pushBalance(receiverId, "GIFT_LUCKY_BONUS", bonus);
                sarpanch.send(receiverId, new SarpanchService.Message("Lucky coins!", "🍀 A gift brought you " + String.format("%,d", bonus) + " lucky coins.", null, null, null, "REWARD"), "gift-lucky:" + requestId);
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
