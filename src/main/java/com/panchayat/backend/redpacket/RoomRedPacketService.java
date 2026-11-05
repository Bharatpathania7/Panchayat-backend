package com.panchayat.backend.redpacket;

import com.panchayat.backend.auth.repository.UserRepository;
import com.panchayat.backend.rooms.service.RoomSettingsJson;
import com.panchayat.backend.voice.entity.VoiceRoom;
import com.panchayat.backend.voice.repository.VoiceRoomParticipantRepository;
import com.panchayat.backend.voice.repository.VoiceRoomRepository;
import com.panchayat.backend.wallet.service.CoinWalletService;
import com.panchayat.backend.wallet.service.WalletPushService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Red packets in rooms, run by the server so everyone sees the same rain and
 * the coins are real.
 *
 * Drop: the sender pays with a packet from their Bag (bought with rupees) of
 * that size, or with coins. After the countdown it rains for RAIN_SECONDS.
 * Grab: each person in the room may grab up to MAX_GRABS times during the
 * rain. Each grab takes a random share of what's left, at most twice the
 * average share still left (the classic fair split), so the pot lasts the
 * whole rain and nobody drains it alone. The sender can loot it too.
 * End: when the rain is over, everyone's grabs are paid into their wallet in
 * one go, and whatever nobody grabbed goes back to the sender.
 *
 * Everyone in the room sees it on /topic/voice-rooms/{id}/redpacket; the whole
 * app gets a banner on /topic/redpackets.
 */
@Service
public class RoomRedPacketService {

    private static final Logger log = LoggerFactory.getLogger(RoomRedPacketService.class);
    public static final int RAIN_SECONDS = 15;
    public static final int MAX_GRABS = 10;
    private static final Set<Integer> DELAYS = Set.of(10, 30, 60, 180);
    private static final Set<String> COVERS = Set.of("red", "royal", "maharaja", "genda", "mor");

    private final JdbcTemplate jdbc;
    private final VoiceRoomRepository rooms;
    private final VoiceRoomParticipantRepository participants;
    private final UserRepository users;
    private final CoinWalletService wallet;
    private final WalletPushService walletPush;
    private final SimpMessagingTemplate messaging;
    private final TransactionTemplate tx;
    private final SecureRandom random = new SecureRandom();
    private final ScheduledExecutorService settler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "red-packet-settler");
        t.setDaemon(true);
        return t;
    });

    public RoomRedPacketService(JdbcTemplate jdbc, VoiceRoomRepository rooms, VoiceRoomParticipantRepository participants,
                                UserRepository users, CoinWalletService wallet, WalletPushService walletPush,
                                SimpMessagingTemplate messaging, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.rooms = rooms;
        this.participants = participants;
        this.users = users;
        this.wallet = wallet;
        this.walletPush = walletPush;
        this.messaging = messaging;
        this.tx = tx;
        // Rains that ended: refund the rest to the sender (also catches drops from before a restart)
        settler.scheduleWithFixedDelay(this::settleEnded, 5, 3, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        settler.shutdownNow();
    }

    /* ---------------- drop ---------------- */

    @Transactional
    public Map<String, Object> drop(Long roomId, Long senderId, long amount, String cover, int delaySeconds) {
        if (!DELAYS.contains(delaySeconds)) throw bad("The rain can start in 10, 30, 60 or 180 seconds.");
        String c = cover == null || !COVERS.contains(cover) ? "red" : cover;
        Boolean sold = jdbc.queryForObject("select exists (select 1 from red_packet_products where active and coin_value = ?)", Boolean.class, amount);
        if (!Boolean.TRUE.equals(sold)) throw bad("There's no red packet of that size.");

        VoiceRoom room = rooms.findByIdForUpdate(roomId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found"));
        if (!"ACTIVE".equals(room.getStatus())) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "This room is closed");
        if (participants.findByRoomIdAndUserIdAndStatus(roomId, senderId, "JOINED").isEmpty()) throw forbidden("Join the room to drop a red packet.");
        Object off = RoomSettingsJson.read(room.getSettings()).get("banRedPacket");
        if (Boolean.TRUE.equals(off)) throw conflict("Red packets are turned off in this room.");
        Boolean busy = jdbc.queryForObject("select exists (select 1 from room_red_packets where room_id = ? and not settled)", Boolean.class, roomId);
        if (Boolean.TRUE.equals(busy)) throw conflict("Wait for this red packet rain to finish.");

        // Red packets are only sent from the Bag (bought in the Shop); coins can't be used directly
        List<Long> bag = jdbc.queryForList(
                "select i.id from user_red_packet_inventory i join red_packet_products p on p.id = i.red_packet_product_id"
                        + " where i.user_id = ? and p.coin_value = ? and i.quantity > 0 order by i.id limit 1 for update of i",
                Long.class, senderId, amount);
        if (bag.isEmpty()) throw conflict("You don't have this red packet. Buy it in the Shop first; it goes to your Bag.");
        jdbc.update("update user_red_packet_inventory set quantity = quantity - 1, updated_at = now() where id = ?", bag.get(0));
        String paidWith = "BAG";

        Instant now = Instant.now();
        Instant rainAt = now.plusSeconds(delaySeconds);
        Instant endAt = rainAt.plusSeconds(RAIN_SECONDS);
        int shares = shares(amount);
        Long id = jdbc.queryForObject(
                "insert into room_red_packets (room_id, sender_id, amount, cover, paid_with, coins_left, shares_left, rain_at, end_at)"
                        + " values (?, ?, ?, ?, ?, ?, ?, ?, ?) returning id",
                Long.class, roomId, senderId, amount, c, paidWith, amount, shares, Timestamp.from(rainAt), Timestamp.from(endAt));

        Map<String, Object> view = view(id, "DROPPED", null, null);
        view.put("roomName", room.getName());
        afterCommit(() -> {
            broadcast(roomId, view);
            Object banner = view;
            messaging.convertAndSend("/topic/redpackets", banner);
            if ("COINS".equals(paidWith)) walletPush.pushBalance(senderId, "RED_PACKET_SENT", -amount);
        });
        return view;
    }

    /* ---------------- grab ---------------- */

    @Transactional
    public Map<String, Object> grab(Long packetId, Long userId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select room_id, sender_id, coins_left, shares_left, rain_at, end_at, settled from room_red_packets where id = ? for update", packetId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "This red packet is gone.");
        Map<String, Object> p = rows.get(0);
        long roomId = ((Number) p.get("room_id")).longValue();
        long left = ((Number) p.get("coins_left")).longValue();
        int sharesLeft = ((Number) p.get("shares_left")).intValue();
        Instant rainAt = ((Timestamp) p.get("rain_at")).toInstant();
        Instant endAt = ((Timestamp) p.get("end_at")).toInstant();
        Instant now = Instant.now();

        // The sender can loot their own packet too, like everyone else in the room
        if (participants.findByRoomIdAndUserIdAndStatus(roomId, userId, "JOINED").isEmpty()) throw forbidden("Join the room to grab.");
        if (now.isBefore(rainAt)) throw conflict("It hasn't started raining yet.");
        long mine = mine(packetId, userId);
        int myGrabs = myGrabs(packetId, userId);
        if (Boolean.TRUE.equals(p.get("settled")) || now.isAfter(endAt) || left <= 0 || sharesLeft <= 0) {
            return result(packetId, 0, mine, myGrabs, left);
        }
        if (myGrabs >= MAX_GRABS) return result(packetId, 0, mine, myGrabs, left);

        // Fair split: the last share takes the rest; otherwise 1 .. 2× the average still left
        long got = sharesLeft == 1 ? left : Math.max(1, Math.min(left, 1 + (long) (random.nextDouble() * Math.max(1, 2 * left / sharesLeft))));
        long newLeft = left - got;
        jdbc.update("update room_red_packets set coins_left = ?, shares_left = shares_left - 1 where id = ?", newLeft, packetId);
        jdbc.update("insert into room_red_packet_grabs (packet_id, user_id, coins) values (?, ?, ?)", packetId, userId, got);
        // Paid into the wallet when the rain ends (settle), all grabs together

        Map<String, Object> view = view(packetId, "GRABBED", userId, got);
        afterCommit(() -> broadcast(roomId, view));
        return result(packetId, got, mine + got, myGrabs + 1, newLeft);
    }

    /** The red packet raining (or counting down) in a room now, with what you grabbed from it. */
    @Transactional(readOnly = true)
    public Map<String, Object> current(Long roomId, Long userId) {
        List<Long> ids = jdbc.queryForList(
                "select id from room_red_packets where room_id = ? and (not settled or end_at > now() - interval '10 seconds')"
                        + " order by created_at desc limit 1", Long.class, roomId);
        if (ids.isEmpty()) return Map.of("packet", Map.of());
        Map<String, Object> v = view(ids.get(0), "CURRENT", null, null);
        v.put("mine", mine(ids.get(0), userId));
        v.put("myGrabs", myGrabs(ids.get(0), userId));
        return Map.of("packet", v);
    }

    /* ---------------- end of rain ---------------- */

    void settleEnded() {
        try {
            List<Long> ended = jdbc.queryForList("select id from room_red_packets where not settled and end_at < now() order by id limit 50", Long.class);
            for (Long id : ended) {
                try {
                    tx.executeWithoutResult(s -> settle(id));
                } catch (Exception e) {
                    log.warn("Couldn't settle red packet {}: {}", id, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.debug("Red packet settler: {}", e.getMessage());
        }
    }

    private void settle(Long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select room_id, sender_id, coins_left, settled from room_red_packets where id = ? for update", id);
        if (rows.isEmpty() || Boolean.TRUE.equals(rows.get(0).get("settled"))) return;
        Map<String, Object> p = rows.get(0);
        long left = ((Number) p.get("coins_left")).longValue();
        long sender = ((Number) p.get("sender_id")).longValue();
        long roomId = ((Number) p.get("room_id")).longValue();
        // Everyone's grabs, paid now that the rain is over (one credit each)
        List<Map<String, Object>> won = jdbc.queryForList(
                "select user_id, sum(coins) as coins from room_red_packet_grabs where packet_id = ? group by user_id", id);
        Map<Long, Long> paid = new LinkedHashMap<>();
        for (Map<String, Object> w : won) {
            long uid = ((Number) w.get("user_id")).longValue();
            long coins = ((Number) w.get("coins")).longValue();
            if (coins <= 0) continue;
            wallet.credit(uid, coins, "RED_PACKET_GRAB", "redpacket:" + id, "rp-win:" + id + ":" + uid, "Won from red packet " + id);
            paid.put(uid, coins);
        }
        if (left > 0) {
            wallet.credit(sender, left, "RED_PACKET_REFUND", "redpacket:" + id, "rp-refund:" + id, "Unclaimed coins from red packet " + id);
        }
        jdbc.update("update room_red_packets set settled = true, refunded = ?, coins_left = 0 where id = ?", left, id);
        Map<String, Object> view = view(id, "ENDED", null, null);
        view.put("refunded", left);
        afterCommit(() -> {
            broadcast(roomId, view);
            paid.forEach((uid, coins) -> walletPush.pushBalance(uid, "RED_PACKET_GRAB", coins));
            if (left > 0) walletPush.pushBalance(sender, "RED_PACKET_REFUND", left);
        });
    }

    /* ---------------- internals ---------------- */

    /** How many shares a pot splits into: about one per 250 coins, between 40 and 300 (enough to tap through the whole rain). */
    static int shares(long amount) {
        return (int) Math.max(40, Math.min(300, amount / 250));
    }

    private long mine(Long packetId, Long userId) {
        Long v = jdbc.queryForObject("select coalesce(sum(coins), 0) from room_red_packet_grabs where packet_id = ? and user_id = ?", Long.class, packetId, userId);
        return v == null ? 0 : v;
    }

    private int myGrabs(Long packetId, Long userId) {
        Integer v = jdbc.queryForObject("select count(*) from room_red_packet_grabs where packet_id = ? and user_id = ?", Integer.class, packetId, userId);
        return v == null ? 0 : v;
    }

    private Map<String, Object> result(Long packetId, long got, long mine, int grabs, long left) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("packetId", String.valueOf(packetId));
        m.put("got", got);
        m.put("mine", mine);
        m.put("myGrabs", grabs);
        m.put("maxGrabs", MAX_GRABS);
        m.put("left", left);
        return m;
    }

    private Map<String, Object> view(Long id, String event, Long grabber, Long got) {
        Map<String, Object> r = jdbc.queryForMap(
                "select p.id, p.room_id, p.sender_id, u.full_name, p.amount, p.cover, p.coins_left, p.shares_left, p.rain_at, p.end_at, p.settled,"
                        + " (select count(distinct g.user_id) from room_red_packet_grabs g where g.packet_id = p.id) as grabbers"
                        + " from room_red_packets p left join users u on u.id = p.sender_id where p.id = ?", id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(id));
        m.put("roomId", String.valueOf(r.get("room_id")));
        m.put("senderId", String.valueOf(r.get("sender_id")));
        m.put("senderName", r.get("full_name"));
        m.put("amount", r.get("amount"));
        m.put("cover", r.get("cover"));
        m.put("left", r.get("coins_left"));
        m.put("grabbers", r.get("grabbers"));
        m.put("rainAt", ((Timestamp) r.get("rain_at")).toInstant().toEpochMilli());
        m.put("endAt", ((Timestamp) r.get("end_at")).toInstant().toEpochMilli());
        m.put("settled", r.get("settled"));
        m.put("maxGrabs", MAX_GRABS);
        m.put("serverTime", System.currentTimeMillis());
        m.put("event", event);
        if (grabber != null) {
            m.put("grabberId", String.valueOf(grabber));
            m.put("got", got);
        }
        return m;
    }

    private void broadcast(Long roomId, Map<String, Object> body) {
        Object payload = body;
        messaging.convertAndSend("/topic/voice-rooms/" + roomId + "/redpacket", payload);
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

    private static ResponseStatusException conflict(String m) {
        return new ResponseStatusException(HttpStatus.CONFLICT, m);
    }

    private static ResponseStatusException forbidden(String m) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, m);
    }
}
