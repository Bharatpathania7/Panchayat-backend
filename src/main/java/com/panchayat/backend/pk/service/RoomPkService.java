package com.panchayat.backend.pk.service;

import com.panchayat.backend.rooms.service.RoomStateService;
import com.panchayat.backend.voice.entity.VoiceRoom;
import com.panchayat.backend.voice.repository.VoiceRoomParticipantRepository;
import com.panchayat.backend.voice.repository.VoiceRoomRepository;
import jakarta.annotation.PreDestroy;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.concurrent.*;

/**
 * PK battles in a voice room: red against blue, run by the server.
 *
 * - The owner or an admin sets one up (1v1 or team, by gifts or by votes,
 *   3–60 minutes), starts it and can close it. The server ends it on time.
 * - By gifts: a gift to someone on a PK seat scores for that seat's side,
 *   1 point per charm (1 charm per 5 coins), from the real gift.
 * - By votes: everyone in the room backs one side, once per round.
 * - Sides by seat (same as the app): seat 0 red, seat 1 blue; in team mode the
 *   other seats go in pairs, alternating red and blue.
 *
 * State lives in memory; every change goes to /topic/voice-rooms/{id}/pk.
 */
@Service
public class RoomPkService {

    private static final Set<Integer> MINUTES = Set.of(3, 5, 10, 15, 30, 60);
    private static final long COINS_PER_CHARM = 5;

    private final VoiceRoomRepository roomRepository;
    private final VoiceRoomParticipantRepository participants;
    private final RoomStateService roomState;
    private final SimpMessagingTemplate messaging;

    private final Map<Long, Pk> battles = new ConcurrentHashMap<>();
    private final Map<Long, Long> versions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "pk-timer");
        t.setDaemon(true);
        return t;
    });

    public RoomPkService(VoiceRoomRepository roomRepository, VoiceRoomParticipantRepository participants,
                         RoomStateService roomState, SimpMessagingTemplate messaging) {
        this.roomRepository = roomRepository;
        this.participants = participants;
        this.roomState = roomState;
        this.messaging = messaging;
    }

    private static final class Pk {
        String mode;
        String rule;
        int minutes;
        String phase = "ready";
        long endsAt;
        long red;
        long blue;
        final Map<String, Map<Long, Long>> backers = Map.of("red", new HashMap<>(), "blue", new HashMap<>());
        final Map<Long, String> votes = new HashMap<>();
        String winner;
    }

    public static String sideOfSeat(String mode, int index) {
        if (index == 0) return "red";
        if (index == 1) return "blue";
        if (!"team".equals(mode) || index < 0) return null;
        return ((index - 2) / 2) % 2 == 0 ? "red" : "blue";
    }

    public Map<String, Object> get(Long roomId) {
        activeRoom(roomId);
        Pk pk = battles.get(roomId);
        if (pk == null) return snapshot(roomId, null, null, null);
        synchronized (pk) {
            return snapshot(roomId, pk, null, null);
        }
    }

    public Map<String, Object> setup(Long roomId, Long actor, String mode, String rule, Integer minutes) {
        VoiceRoom room = activeRoom(roomId);
        requireMod(room, actor);
        if (!"1v1".equals(mode) && !"team".equals(mode)) throw bad("mode must be 1v1 or team");
        if (!"gifts".equals(rule) && !"votes".equals(rule)) throw bad("rule must be gifts or votes");
        if (minutes == null || !MINUTES.contains(minutes)) throw bad("Pick 3, 5, 10, 15, 30 or 60 minutes");
        Pk old = battles.get(roomId);
        if (old != null) {
            synchronized (old) {
                if ("live".equals(old.phase)) throw conflict("A PK is already running. Close it first.");
            }
        }
        Pk pk = new Pk();
        pk.mode = mode;
        pk.rule = rule;
        pk.minutes = minutes;
        battles.put(roomId, pk);
        synchronized (pk) {
            return publish(roomId, pk, "SETUP", actor);
        }
    }

    public Map<String, Object> start(Long roomId, Long actor) {
        VoiceRoom room = activeRoom(roomId);
        requireMod(room, actor);
        Pk pk = battles.get(roomId);
        if (pk == null) throw conflict("Set up a PK first");
        synchronized (pk) {
            if (!"ready".equals(pk.phase)) throw conflict("This PK has already started");
            pk.phase = "live";
            pk.endsAt = System.currentTimeMillis() + pk.minutes * 60_000L;
            timer.schedule(() -> finish(roomId, pk), pk.minutes * 60_000L + 200, TimeUnit.MILLISECONDS);
            return publish(roomId, pk, "STARTED", actor);
        }
    }

    public Map<String, Object> close(Long roomId, Long actor) {
        VoiceRoom room = activeRoom(roomId);
        requireMod(room, actor);
        battles.remove(roomId);
        return publish(roomId, null, "CLOSED", actor);
    }

    public Map<String, Object> vote(Long roomId, Long actor, String side) {
        activeRoom(roomId);
        if (participants.findByRoomIdAndUserIdAndStatus(roomId, actor, "JOINED").isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Join the room first");
        }
        if (!"red".equals(side) && !"blue".equals(side)) throw bad("side must be red or blue");
        Pk pk = battles.get(roomId);
        if (pk == null) throw conflict("No PK is running");
        synchronized (pk) {
            if (!"live".equals(pk.phase) || !"votes".equals(pk.rule)) throw conflict("This PK isn't taking votes");
            if (pk.votes.containsKey(actor)) throw conflict("You already voted this round");
            pk.votes.put(actor, side);
            score(pk, side, actor, 1);
            return publish(roomId, pk, "VOTE", actor);
        }
    }

    /** Called after a real gift is committed. */
    public void onGift(Long roomId, Long from, Long to, long coins) {
        Pk pk = battles.get(roomId);
        if (pk == null) return;
        synchronized (pk) {
            if (!"live".equals(pk.phase) || !"gifts".equals(pk.rule)) return;
            String side = sideOfSeat(pk.mode, roomState.seatIndexOf(roomId, to));
            if (side == null) return;
            score(pk, side, from, Math.max(1, coins / COINS_PER_CHARM));
            publish(roomId, pk, "SCORE", from);
        }
    }

    private void finish(Long roomId, Pk pk) {
        synchronized (pk) {
            if (battles.get(roomId) != pk || !"live".equals(pk.phase)) return;
            if (System.currentTimeMillis() < pk.endsAt) {
                timer.schedule(() -> finish(roomId, pk), pk.endsAt - System.currentTimeMillis() + 50, TimeUnit.MILLISECONDS);
                return;
            }
            pk.phase = "done";
            pk.winner = pk.red == pk.blue ? "draw" : pk.red > pk.blue ? "red" : "blue";
            publish(roomId, pk, "ENDED", null);
        }
    }

    private static void score(Pk pk, String side, Long by, long points) {
        if ("red".equals(side)) pk.red += points; else pk.blue += points;
        pk.backers.get(side).merge(by, points, Long::sum);
    }

    private Map<String, Object> publish(Long roomId, Pk pk, String event, Long actor) {
        versions.merge(roomId, 1L, Long::sum);
        Map<String, Object> state = snapshot(roomId, pk, event, actor);
        Object payload = state;
        messaging.convertAndSend("/topic/voice-rooms/" + roomId + "/pk", payload);
        return state;
    }

    private Map<String, Object> snapshot(Long roomId, Pk pk, String event, Long actor) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("roomId", String.valueOf(roomId));
        out.put("version", versions.getOrDefault(roomId, 0L));
        out.put("serverTime", System.currentTimeMillis());
        if (pk == null) {
            out.put("pk", null);
        } else {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("mode", pk.mode);
            p.put("rule", pk.rule);
            p.put("minutes", pk.minutes);
            p.put("phase", pk.phase);
            p.put("endsAt", pk.endsAt);
            p.put("red", pk.red);
            p.put("blue", pk.blue);
            Map<String, Object> backers = new LinkedHashMap<>();
            for (String side : List.of("red", "blue")) {
                Map<String, Long> m = new LinkedHashMap<>();
                pk.backers.get(side).entrySet().stream()
                        .sorted(Map.Entry.<Long, Long>comparingByValue().reversed())
                        .limit(20)
                        .forEach(e -> m.put(String.valueOf(e.getKey()), e.getValue()));
                backers.put(side, m);
            }
            p.put("backers", backers);
            Map<String, String> votes = new LinkedHashMap<>();
            pk.votes.forEach((k, v) -> votes.put(String.valueOf(k), v));
            p.put("votes", votes);
            p.put("winner", pk.winner);
            out.put("pk", p);
        }
        if (event != null) out.put("event", Map.of("type", event, "actorId", actor == null ? "" : String.valueOf(actor)));
        return out;
    }

    private void requireMod(VoiceRoom room, Long actor) {
        if (!roomState.isModerator(room, actor)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the owner or an admin can run a PK");
        if (participants.findByRoomIdAndUserIdAndStatus(room.getId(), actor, "JOINED").isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Be in the room to do this");
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

    private static ResponseStatusException conflict(String m) {
        return new ResponseStatusException(HttpStatus.CONFLICT, m);
    }
}
