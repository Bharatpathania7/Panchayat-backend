package com.panchayat.backend.gifts.service;

import com.panchayat.backend.gifts.entity.RoomGiftEvent;
import com.panchayat.backend.gifts.repository.RoomGiftEventRepository;
import com.panchayat.backend.voice.entity.VoiceRoom;
import com.panchayat.backend.voice.repository.VoiceRoomParticipantRepository;
import com.panchayat.backend.voice.repository.VoiceRoomRepository;
import com.panchayat.backend.wallet.service.CoinWalletService;
import com.panchayat.backend.wallet.service.WalletPushService;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Sending gifts in a room, with real coins.
 *
 * One transaction: take the gift's price from the sender, give the receiver
 * their share, record the gift and add room points. If any step fails,
 * nothing happens. The price comes from the server's catalog, never from the
 * app. A send carries the app's requestId: retrying the same send (a network
 * blip) never charges twice.
 *
 * After it commits, everyone in the room gets the gift on
 * /topic/voice-rooms/{roomId}/gifts, and the sender's and receiver's new
 * balances go to their own /user/queue/wallet.
 */
@Service
public class RoomGiftService {

    private static final Pattern REQUEST_ID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");
    private static final int MAX_COUNT = 9999;
    private static final ZoneId BOARD_ZONE = ZoneId.of("Asia/Kolkata");

    private final RoomGiftEventRepository events;
    private final VoiceRoomRepository rooms;
    private final VoiceRoomParticipantRepository participants;
    private final CoinWalletService wallet;
    private final WalletPushService walletPush;
    private final SimpMessagingTemplate messaging;
    private final com.panchayat.backend.tags.service.UserTagService tags;
    private final com.panchayat.backend.pk.service.RoomPkService pk;
    private final com.panchayat.backend.auction.service.RoomAuctionService auction;

    public RoomGiftService(RoomGiftEventRepository events, VoiceRoomRepository rooms,
                           VoiceRoomParticipantRepository participants, CoinWalletService wallet,
                           WalletPushService walletPush, SimpMessagingTemplate messaging,
                           com.panchayat.backend.tags.service.UserTagService tags,
                           com.panchayat.backend.pk.service.RoomPkService pk,
                           com.panchayat.backend.auction.service.RoomAuctionService auction) {
        this.events = events;
        this.rooms = rooms;
        this.participants = participants;
        this.wallet = wallet;
        this.walletPush = walletPush;
        this.messaging = messaging;
        this.tags = tags;
        this.pk = pk;
        this.auction = auction;
    }

    public record GiftSent(String eventId, String roomId, String fromUserId, String toUserId, String giftId,
                           int count, long coins, long earned, long roomPoints, String requestId,
                           Instant at, long senderBalance) {
    }

    @Transactional
    public GiftSent send(Long roomId, Long senderId, String giftId, Long receiverId, int count, String requestId) {
        if (requestId == null || !REQUEST_ID.matcher(requestId).matches()) throw bad("A request id is required");
        if (receiverId == null || receiverId <= 0) throw bad("Choose who gets the gift");
        if (count < 1 || count > MAX_COUNT) throw bad("Send between 1 and " + MAX_COUNT + " at a time");
        long price = GiftCatalog.price(giftId).orElseThrow(() -> bad("That gift isn't on sale"));

        // A retry of a send that already went through: same answer, no new charge
        Optional<RoomGiftEvent> done = events.findByRequestId(requestId);
        if (done.isPresent()) {
            RoomGiftEvent e = done.get();
            if (!e.getSenderId().equals(senderId)) throw bad("That request id was already used");
            long points = rooms.findById(e.getRoomId()).map(VoiceRoom::getPoints).orElse(0L);
            return toResult(e, points, wallet.getBalance(senderId), requestId);
        }

        VoiceRoom room = rooms.findById(roomId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Voice room not found"));
        if (!"ACTIVE".equals(room.getStatus())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Voice room is not available");
        }
        if (senderId.equals(receiverId)) throw bad("You can't send a gift to yourself");
        if (participants.findByRoomIdAndUserIdAndStatus(roomId, senderId, "JOINED").isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Join the room to send gifts");
        }
        if (participants.findByRoomIdAndUserIdAndStatus(roomId, receiverId, "JOINED").isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "They're no longer in the room");
        }

        long coins;
        long points;
        try {
            coins = Math.multiplyExact(price, count);
            points = Math.multiplyExact(coins, GiftCatalog.ROOM_POINTS_PER_COIN);
        } catch (ArithmeticException e) {
            throw bad("That's too many at once");
        }
        long earned = coins * GiftCatalog.RECEIVER_SHARE_PERCENT / 100;
        String ref = "room:" + roomId;

        try {
            wallet.debit(senderId, coins, "GIFT_SENT", ref, "gift:" + requestId + ":send",
                    count + "x " + giftId + " to user " + receiverId);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Not enough coins. Recharge to send this gift.");
        }
        if (earned > 0) {
            wallet.credit(receiverId, earned, "GIFT_EARNINGS", ref, "gift:" + requestId + ":earn",
                    "From user " + senderId + ": " + count + "x " + giftId);
        }

        RoomGiftEvent saved = events.save(new RoomGiftEvent(roomId, senderId, receiverId, giftId, count, coins, earned, requestId));
        events.flush();
        rooms.addPoints(roomId, points);
        // Gifts between two tagged people grow their tag (same transaction)
        tags.addIntimacy(senderId, receiverId, coins);
        long roomPoints = rooms.findById(roomId).map(VoiceRoom::getPoints).orElse(0L);
        GiftSent result = toResult(saved, roomPoints, wallet.getBalance(senderId), requestId);

        afterCommit(() -> {
            Object payload = result;
            messaging.convertAndSend("/topic/voice-rooms/" + roomId + "/gifts", payload);
            walletPush.pushBalance(senderId, "GIFT_SENT", -coins);
            if (earned > 0) walletPush.pushBalance(receiverId, "GIFT_EARNINGS", earned);
            // Room games: PK points and Kharidari bids come from real gifts
            pk.onGift(roomId, senderId, receiverId, coins);
            auction.onGift(roomId, senderId, receiverId, coins);
        });
        return result;
    }

    /** period: daily (today, India time), weekly (since Monday), total. */
    @Transactional(readOnly = true)
    public Map<String, Object> contribution(Long roomId, Long me, String period) {
        String p = period == null ? "total" : period.toLowerCase(Locale.ROOT);
        Instant since = switch (p) {
            case "daily" -> LocalDate.now(BOARD_ZONE).atStartOfDay(BOARD_ZONE).toInstant();
            case "weekly" -> LocalDate.now(BOARD_ZONE)
                    .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    .atStartOfDay(BOARD_ZONE).toInstant();
            case "total" -> Instant.EPOCH;
            default -> throw bad("period must be daily, weekly or total");
        };
        List<Object[]> all = events.contributionSince(roomId, since, PageRequest.of(0, 500));
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> mine = new LinkedHashMap<>();
        mine.put("coins", 0L);
        mine.put("rank", null);
        for (int i = 0; i < all.size(); i++) {
            Long userId = ((Number) all.get(i)[0]).longValue();
            long coins = ((Number) all.get(i)[1]).longValue();
            if (i < 50) rows.add(Map.of("userId", String.valueOf(userId), "coins", coins, "rank", i + 1));
            if (userId.equals(me)) {
                mine.put("coins", coins);
                mine.put("rank", i + 1);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("period", p);
        out.put("rows", rows);
        out.put("me", mine);
        return out;
    }

    private static GiftSent toResult(RoomGiftEvent e, long roomPoints, long senderBalance, String requestId) {
        return new GiftSent(String.valueOf(e.getId()), String.valueOf(e.getRoomId()), String.valueOf(e.getSenderId()),
                String.valueOf(e.getReceiverId()), e.getGiftId(), e.getGiftCount(), e.getCoins(), e.getReceiverEarned(),
                roomPoints, requestId, e.getCreatedAt(), senderBalance);
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
