package com.panchayat.backend.gifts.service;

import com.panchayat.backend.rewards.service.SpendRewardService;
import com.panchayat.backend.tags.service.UserTagService;
import com.panchayat.backend.wallet.service.CoinWalletService;
import com.panchayat.backend.wallet.service.WalletPushService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Gifts sent outside rooms: from a profile, the BFF space, the CP space or a
 * Moment. Same money rules as room gifts (price from the server catalog, the
 * receiver's share, charm, VIP growth, spend rewards, BFF intimacy) and the
 * same history table with no room, so rankings count them too.
 *
 * The receiver hears about it on /user/queue/notify ({type: "GIFT", …}).
 */
@Service
public class DirectGiftService {
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private com.panchayat.backend.progress.tasks.DailyTaskService dailyTasks;


    @org.springframework.beans.factory.annotation.Autowired
    private com.panchayat.backend.vip.service.VipGrowthService vipGrowth;


    private static final Pattern REQUEST_ID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");
    private static final Set<String> CONTEXTS = Set.of("PROFILE", "TAG", "CP", "MOMENT", "CHAT");
    private static final int MAX_COUNT = 999;
    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    private final JdbcTemplate jdbc;
    private final CoinWalletService wallet;
    private final WalletPushService walletPush;
    private final SimpMessagingTemplate messaging;
    private final UserTagService tags;
    private final SpendRewardService rewards;

    @org.springframework.beans.factory.annotation.Autowired
    private com.panchayat.backend.cp.CpService couples;

    public DirectGiftService(JdbcTemplate jdbc, CoinWalletService wallet, WalletPushService walletPush,
                             SimpMessagingTemplate messaging, UserTagService tags, SpendRewardService rewards) {
        this.jdbc = jdbc;
        this.wallet = wallet;
        this.walletPush = walletPush;
        this.messaging = messaging;
        this.tags = tags;
        this.rewards = rewards;
    }

    @Transactional
    public Map<String, Object> send(Long senderId, Long receiverId, String giftId, int count, String requestId, String context) {
        if (requestId == null || !REQUEST_ID.matcher(requestId).matches()) throw bad("A request id is required");
        if (receiverId == null || receiverId <= 0) throw bad("Choose who gets the gift");
        // Self-gifting is allowed (it doesn't count toward guard, tags or CP)
        if (count < 1 || count > MAX_COUNT) throw bad("Send between 1 and " + MAX_COUNT + " at a time");
        String ctx = context == null ? "PROFILE" : context.toUpperCase(Locale.ROOT);
        if (!CONTEXTS.contains(ctx)) ctx = "PROFILE";
        long price = GiftCatalog.price(giftId).orElseThrow(() -> bad("That gift isn't on sale"));

        // A retry of a send that already went through: same answer, no new charge
        List<Map<String, Object>> done = jdbc.queryForList(
                "select id, sender_id, coins, receiver_earned from room_gift_events where request_id = ?", requestId);
        if (!done.isEmpty()) {
            if (!senderId.equals(((Number) done.get(0).get("sender_id")).longValue())) throw bad("That request id was already used");
            return result(((Number) done.get(0).get("id")).longValue(), ((Number) done.get(0).get("coins")).longValue(),
                    ((Number) done.get(0).get("receiver_earned")).longValue(), wallet.getBalance(senderId));
        }

        Integer exists = jdbc.queryForObject("select count(*) from users where id = ?", Integer.class, receiverId);
        if (exists == null || exists == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That person doesn't exist");
        Integer blocks = jdbc.queryForObject(
                "select count(*) from user_blocks where (blocker_id = ? and blocked_id = ?) or (blocker_id = ? and blocked_id = ?)",
                Integer.class, senderId, receiverId, receiverId, senderId);
        if (blocks != null && blocks > 0) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can't send gifts to this person.");

        long coins;
        try {
            coins = Math.multiplyExact(price, count);
        } catch (ArithmeticException e) {
            throw bad("That's too many at once");
        }
        long earned = coins * GiftCatalog.RECEIVER_SHARE_PERCENT / 100;
        String ref = "gift:" + ctx.toLowerCase(Locale.ROOT);
        try {
            wallet.debit(senderId, coins, "GIFT_SENT", ref, "gift:" + requestId + ":send", count + "x " + giftId + " to user " + receiverId);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Not enough coins. Recharge to send this gift.");
        }
        if (earned > 0) {
            wallet.credit(receiverId, earned, "GIFT_EARNINGS", ref, "gift:" + requestId + ":earn", "From user " + senderId + ": " + count + "x " + giftId);
        }
        Long id = jdbc.queryForObject(
                "insert into room_gift_events (room_id, sender_id, receiver_id, gift_id, gift_count, coins, receiver_earned, request_id, context)"
                        + " values (null, ?, ?, ?, ?, ?, ?, ?, ?) returning id",
                Long.class, senderId, receiverId, giftId, count, coins, earned, requestId, ctx);

        tags.addIntimacy(senderId, receiverId, coins);
        couples.onGift(senderId, receiverId, coins);
        rewards.onSpend(senderId, coins);
        // VIP growth, with the level-up reward notice
        vipGrowth.onSpend(senderId, coins);
        dailyTasks.track(senderId, "SEND_GIFT", 1);
        int charmFactor = LocalDate.now(ZONE).getDayOfWeek().getValue() >= 6 ? 2 : 1;
        long charm = coins * charmFactor / 5;
        jdbc.update("update users set charm_points = charm_points + ? where id = ?", charm, receiverId);
        // The same number counts as the sender's Guard for the receiver (Guard list)
        jdbc.update("update room_gift_events set charm = ? where request_id = ?", charm, requestId);

        long balance = wallet.getBalance(senderId);
        String senderName = jdbc.queryForObject("select coalesce(full_name, 'Someone') from users where id = ?", String.class, senderId);
        String receiverEmail = jdbc.queryForObject("select email from users where id = ?", String.class, receiverId);
        final long fCoins = coins;
        final String fCtx = ctx;
        afterCommit(() -> {
            walletPush.pushBalance(senderId, "GIFT_SENT", -fCoins);
            if (earned > 0) walletPush.pushBalance(receiverId, "GIFT_EARNINGS", earned);
            if (receiverEmail != null) {
                Map<String, Object> note = new LinkedHashMap<>();
                note.put("type", "GIFT");
                note.put("fromUserId", String.valueOf(senderId));
                note.put("fromName", senderName);
                note.put("giftId", giftId);
                note.put("count", count);
                note.put("coins", fCoins);
                note.put("context", fCtx);
                note.put("at", Instant.now().toString());
                messaging.convertAndSendToUser(receiverEmail, "/queue/notify", note);
            }
        });
        return result(id, coins, earned, balance);
    }

    /** Recent gifts between two people, both ways (for the CP and BFF spaces). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> between(Long a, Long b, int limit) {
        return jdbc.queryForList(
                "select e.id::text as id, e.sender_id::text as \"fromUserId\", e.receiver_id::text as \"toUserId\", e.gift_id as \"giftId\","
                        + " e.gift_count as count, e.coins, e.created_at as at, u.full_name as \"fromName\""
                        + " from room_gift_events e join users u on u.id = e.sender_id"
                        + " where (e.sender_id = ? and e.receiver_id = ?) or (e.sender_id = ? and e.receiver_id = ?)"
                        + " order by e.created_at desc limit ?",
                a, b, b, a, Math.max(1, Math.min(limit, 50)));
    }

    /** Recent gifts someone received from anyone. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> received(Long userId, int limit) {
        return jdbc.queryForList(
                "select e.id::text as id, e.sender_id::text as \"fromUserId\", e.gift_id as \"giftId\", e.gift_count as count, e.coins,"
                        + " e.created_at as at, u.full_name as \"fromName\", u.avatar_url as \"fromAvatar\""
                        + " from room_gift_events e join users u on u.id = e.sender_id where e.receiver_id = ?"
                        + " order by e.created_at desc limit ?",
                userId, Math.max(1, Math.min(limit, 50)));
    }

    private static Map<String, Object> result(long id, long coins, long earned, long balance) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("eventId", String.valueOf(id));
        m.put("coins", coins);
        m.put("earned", earned);
        m.put("senderBalance", balance);
        return m;
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

    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }
}
