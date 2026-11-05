package com.panchayat.backend.auction.service;

import com.panchayat.backend.gifts.service.GiftCatalog;
import com.panchayat.backend.rooms.service.RoomStateService;
import com.panchayat.backend.tags.service.UserTagService;
import com.panchayat.backend.voice.entity.VoiceRoom;
import com.panchayat.backend.voice.repository.VoiceRoomParticipantRepository;
import com.panchayat.backend.voice.repository.VoiceRoomRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.concurrent.*;

/**
 * Kharidari auctions, run by the server.
 *
 * Someone sits on the auction seat (themselves, or the owner/an admin seats
 * them) and declares the tag they want. The owner or an admin picks BFF or
 * Ordinary bidding and a starting-price gift, then starts a 45 s round.
 * Bids are real gifts: every gift sent to the person on the auction seat
 * adds its coin value to the sender's gift volume (not refunded), and a late
 * bid keeps the round open for 10 more seconds. When the server's timer ends
 * the round, the biggest volume that reached the starting price wins. In BFF
 * mode the winner and the person on the seat become a tag (saved).
 *
 * State lives in memory; every change goes to /topic/voice-rooms/{id}/auction.
 */
@Service
public class RoomAuctionService {

    private static final Logger log = LoggerFactory.getLogger(RoomAuctionService.class);
    private static final long ROUND_MS = 45_000;
    private static final long LATE_BID_MS = 10_000;
    private static final long SOLD_SHOW_MS = 9_000;

    private final VoiceRoomRepository roomRepository;
    private final VoiceRoomParticipantRepository participants;
    private final RoomStateService roomState;
    private final UserTagService tags;
    private final SimpMessagingTemplate messaging;

    private final Map<Long, Auction> auctions = new ConcurrentHashMap<>();
    private final Map<Long, Long> versions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "auction-timer");
        t.setDaemon(true);
        return t;
    });

    public RoomAuctionService(VoiceRoomRepository roomRepository, VoiceRoomParticipantRepository participants,
                              RoomStateService roomState, UserTagService tags, SimpMessagingTemplate messaging) {
        this.roomRepository = roomRepository;
        this.participants = participants;
        this.roomState = roomState;
        this.tags = tags;
        this.messaging = messaging;
    }

    private static final class Auction {
        String phase = "idle";
        Long itemId;
        String mode = "bff";
        String relation = "besties";
        String declaration = "";
        String offer = "Sing a song";
        String startGiftId;
        long startPrice;
        long endsAt;
        /** Bidder -> gift volume this round. */
        final Map<Long, Long> bids = new HashMap<>();
        Long winnerId;
        long winnerTotal;
        String tagKind;
        String tagToken;
        long round;
    }

    private Auction of(Long roomId) {
        return auctions.computeIfAbsent(roomId, k -> new Auction());
    }

    public Map<String, Object> get(Long roomId) {
        activeRoom(roomId);
        Auction a = of(roomId);
        synchronized (a) {
            return snapshot(roomId, a, null, null);
        }
    }

    /** userId null = yourself. Seating someone else: the owner or an admin. */
    public Map<String, Object> sit(Long roomId, Long actor, Long userId) {
        VoiceRoom room = activeRoom(roomId);
        Long target = userId == null ? actor : userId;
        if (!target.equals(actor) && !roomState.isModerator(room, actor)) throw forbidden("Only the owner or an admin can seat someone else");
        if (room.getOwnerId().equals(target)) throw conflict("The owner's seat is fixed. Seat someone else on the auction seat.");
        requireInRoom(roomId, actor);
        requireInRoom(roomId, target);
        Auction a = of(roomId);
        synchronized (a) {
            if (!"idle".equals(a.phase) || a.itemId != null) throw conflict("Someone is already on the auction seat");
            a.itemId = target;
            a.bids.clear();
            a.winnerId = null;
            a.tagKind = null;
            a.declaration = "";
        }
        roomState.vacateSeat(roomId, target);
        synchronized (a) {
            return publish(roomId, a, "SAT", target);
        }
    }

    public Map<String, Object> leave(Long roomId, Long actor) {
        VoiceRoom room = activeRoom(roomId);
        Auction a = of(roomId);
        synchronized (a) {
            if (a.itemId == null) return snapshot(roomId, a, null, null);
            if (!a.itemId.equals(actor) && !roomState.isModerator(room, actor)) throw forbidden("Only the person on the seat, the owner or an admin can do this");
            if ("bidding".equals(a.phase)) throw conflict("You can't leave the auction seat while bidding is open");
            Long was = a.itemId;
            reset(a);
            return publish(roomId, a, "LEFT", was);
        }
    }

    public Map<String, Object> declare(Long roomId, Long actor, String relation, String declaration) {
        activeRoom(roomId);
        Auction a = of(roomId);
        synchronized (a) {
            if (!actor.equals(a.itemId)) throw forbidden("Only the person on the auction seat declares the tag");
            if ("bidding".equals(a.phase)) throw conflict("You can't change your declaration while bidding is open");
            if (!UserTagService.RELATIONS.contains(relation)) throw bad("Pick one of the relationships");
            if (tags.hasType(actor, relation)) throw conflict("You already have a " + UserTagService.label(relation) + " tag. Each person can have one tag of each type, so pick another.");
            String text = declaration == null ? "" : declaration.trim();
            a.relation = relation;
            a.declaration = text.length() > 80 ? text.substring(0, 80) : text;
            return publish(roomId, a, "DECLARED", actor);
        }
    }

    public Map<String, Object> configure(Long roomId, Long actor, String mode, String offer, String startGiftId) {
        VoiceRoom room = activeRoom(roomId);
        requireMod(room, actor);
        Auction a = of(roomId);
        synchronized (a) {
            if ("bidding".equals(a.phase)) throw conflict("Settings can't change while bidding is open");
            if (mode != null) {
                if (!"bff".equals(mode) && !"ordinary".equals(mode)) throw bad("mode must be bff or ordinary");
                a.mode = mode;
            }
            if (offer != null) {
                String o = offer.trim();
                if (o.isEmpty() || o.length() > 60) throw bad("The offer must be 1 to 60 characters");
                a.offer = o;
            }
            if (startGiftId != null) {
                a.startPrice = GiftCatalog.price(startGiftId).orElseThrow(() -> bad("That gift isn't on sale"));
                a.startGiftId = startGiftId;
            }
            return publish(roomId, a, "CONFIGURED", actor);
        }
    }

    public Map<String, Object> start(Long roomId, Long actor) {
        VoiceRoom room = activeRoom(roomId);
        requireMod(room, actor);
        Auction a = of(roomId);
        synchronized (a) {
            if ("bidding".equals(a.phase)) throw conflict("Bidding is already open");
            if (a.itemId == null) throw conflict("Seat someone on the auction seat first");
            if (a.startGiftId == null) throw conflict("Pick a starting price gift in Settings first");
            a.phase = "bidding";
            a.bids.clear();
            a.winnerId = null;
            a.tagKind = null;
            a.endsAt = System.currentTimeMillis() + ROUND_MS;
            long round = ++a.round;
            schedule(roomId, a, round, ROUND_MS);
            return publish(roomId, a, "STARTED", actor);
        }
    }

    public Map<String, Object> endNow(Long roomId, Long actor) {
        VoiceRoom room = activeRoom(roomId);
        requireMod(room, actor);
        Auction a = of(roomId);
        synchronized (a) {
            if (!"bidding".equals(a.phase)) throw conflict("No bidding is open");
            a.endsAt = System.currentTimeMillis();
        }
        finish(roomId, a, a.round, room.getName());
        synchronized (a) {
            return snapshot(roomId, a, null, null);
        }
    }

    /** Called after a real gift is committed: a gift to the auction seat is a bid. */
    public void onGift(Long roomId, Long from, Long to, long coins) {
        Auction a = auctions.get(roomId);
        if (a == null) return;
        synchronized (a) {
            if (!"bidding".equals(a.phase) || !to.equals(a.itemId) || from.equals(a.itemId)) return;
            a.bids.merge(from, coins, Long::sum);
            long now = System.currentTimeMillis();
            if (a.endsAt < now + LATE_BID_MS) {
                a.endsAt = now + LATE_BID_MS;
                schedule(roomId, a, a.round, LATE_BID_MS);
            }
            publish(roomId, a, "BID", from);
        }
    }

    private void schedule(Long roomId, Auction a, long round, long delayMs) {
        timer.schedule(() -> {
            String roomName = roomRepository.findById(roomId).map(VoiceRoom::getName).orElse("");
            finish(roomId, a, round, roomName);
        }, delayMs + 100, TimeUnit.MILLISECONDS);
    }

    private void finish(Long roomId, Auction a, long round, String roomName) {
        Long winner;
        long total;
        Long item;
        String mode;
        String relation;
        String declaration;
        synchronized (a) {
            if (!"bidding".equals(a.phase) || a.round != round) return;
            if (System.currentTimeMillis() < a.endsAt) return; // a later timer will close it
            Map.Entry<Long, Long> best = a.bids.entrySet().stream()
                    .filter(e -> e.getValue() >= a.startPrice)
                    .max(Map.Entry.comparingByValue())
                    .orElse(null);
            if (best == null) {
                a.phase = "idle";
                a.bids.clear();
                publish(roomId, a, "NO_SALE", a.itemId);
                return;
            }
            winner = best.getKey();
            total = best.getValue();
            item = a.itemId;
            mode = a.mode;
            relation = a.relation;
            declaration = a.declaration;
            a.phase = "closing";
        }

        String kind = null;
        String token = null;
        if ("bff".equals(mode)) {
            try {
                kind = tags.form(winner, item, relation, total, roomName, declaration).name().toLowerCase(Locale.ROOT);
                token = UserTagService.tokenFor(total);
            } catch (Exception e) {
                log.warn("Tag could not be formed for room {}: {}", roomId, e.getMessage());
            }
        }

        synchronized (a) {
            a.phase = "sold";
            a.winnerId = winner;
            a.winnerTotal = total;
            a.tagKind = kind;
            a.tagToken = token;
            long soldRound = a.round;
            publish(roomId, a, "SOLD", winner);
            // After a sale the auction seat clears for the next round
            timer.schedule(() -> {
                synchronized (a) {
                    if (!"sold".equals(a.phase) || a.round != soldRound) return;
                    reset(a);
                    publish(roomId, a, "CLEARED", null);
                }
            }, SOLD_SHOW_MS, TimeUnit.MILLISECONDS);
        }
    }

    private static void reset(Auction a) {
        a.phase = "idle";
        a.itemId = null;
        a.bids.clear();
        a.winnerId = null;
        a.tagKind = null;
        a.declaration = "";
    }

    private Map<String, Object> publish(Long roomId, Auction a, String event, Long actor) {
        versions.merge(roomId, 1L, Long::sum);
        Map<String, Object> state = snapshot(roomId, a, event, actor);
        Object payload = state;
        messaging.convertAndSend("/topic/voice-rooms/" + roomId + "/auction", payload);
        return state;
    }

    private Map<String, Object> snapshot(Long roomId, Auction a, String event, Long actor) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("roomId", String.valueOf(roomId));
        out.put("version", versions.getOrDefault(roomId, 0L));
        out.put("serverTime", System.currentTimeMillis());
        out.put("phase", "closing".equals(a.phase) ? "bidding" : a.phase);
        out.put("itemId", a.itemId == null ? null : String.valueOf(a.itemId));
        out.put("mode", a.mode);
        out.put("relation", a.relation);
        out.put("declaration", a.declaration);
        out.put("offer", a.offer);
        out.put("startGiftId", a.startGiftId);
        out.put("startPrice", a.startPrice);
        out.put("endsAt", a.endsAt);
        List<Map<String, Object>> bids = new ArrayList<>();
        a.bids.entrySet().stream()
                .sorted(Map.Entry.<Long, Long>comparingByValue().reversed())
                .forEach(e -> bids.add(Map.of("userId", String.valueOf(e.getKey()), "total", e.getValue())));
        out.put("bids", bids);
        out.put("winner", a.winnerId == null ? null : Map.of("userId", String.valueOf(a.winnerId), "total", a.winnerTotal));
        out.put("tagResult", a.tagKind == null ? null : Map.of("kind", a.tagKind, "token", a.tagToken == null ? "bicycle" : a.tagToken));
        if (event != null) out.put("event", Map.of("type", event, "actorId", actor == null ? "" : String.valueOf(actor)));
        return out;
    }

    private void requireMod(VoiceRoom room, Long actor) {
        if (!roomState.isModerator(room, actor)) throw forbidden("Only the owner or an admin can run Kharidari");
        requireInRoom(room.getId(), actor);
    }

    private void requireInRoom(Long roomId, Long userId) {
        if (participants.findByRoomIdAndUserIdAndStatus(roomId, userId, "JOINED").isEmpty()) {
            throw forbidden("They have to be in the room");
        }
    }

    private VoiceRoom activeRoom(Long roomId) {
        if (roomId == null || roomId <= 0) throw bad("Invalid room ID");
        VoiceRoom room = roomRepository.findById(roomId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Voice room not found"));
        if (!"ACTIVE".equals(room.getStatus())) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Voice room is not available");
        return room;
    }

    @PreDestroy
    void shutdown() {
        timer.shutdownNow();
    }

    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }

    private static ResponseStatusException forbidden(String m) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, m);
    }

    private static ResponseStatusException conflict(String m) {
        return new ResponseStatusException(HttpStatus.CONFLICT, m);
    }
}
