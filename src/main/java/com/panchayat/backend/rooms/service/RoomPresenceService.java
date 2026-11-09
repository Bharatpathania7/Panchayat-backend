package com.panchayat.backend.rooms.service;

import com.panchayat.backend.voice.entity.VoiceRoomParticipant;
import com.panchayat.backend.voice.repository.VoiceRoomParticipantRepository;
import com.panchayat.backend.voice.repository.VoiceRoomRepository;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import livekit.LivekitModels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Tells a dropped connection apart from really leaving.
 *
 * The database only knows JOINED and LEFT. Leaving on purpose (POST /leave) is
 * recorded at once. Everyone else is checked against LiveKit, which knows who
 * is really connected: someone missing is DISCONNECTED but stays in the room,
 * on their seat, for the grace period (app.voice.grace-seconds), and only then
 * is marked LEFT. Asking for a token again (reconnecting) restarts the period.
 *
 * It only reads LiveKit's own API, so it needs no webhook or server change, and
 * writes only when someone's state really changes. If LiveKit can't answer,
 * nobody is marked as gone. It also corrects talking rights LiveKit didn't apply.
 *
 * State is kept in memory (one backend instance): after a restart everyone
 * missing simply gets a new grace period.
 */
@Service
public class RoomPresenceService {

    private static final Logger log = LoggerFactory.getLogger(RoomPresenceService.class);

    public enum State { CONNECTING, CONNECTED, DISCONNECTED }

    private record Tracked(State state, Instant since) {
    }

    private final VoiceRoomParticipantRepository participants;
    private final VoiceRoomRepository rooms;
    private final RoomStateService roomState;
    private final LiveKitPermissionService liveKit;
    private final TransactionTemplate tx;
    private final MeterRegistry metrics;
    private final Duration grace;
    private final long sweepSeconds;
    private final boolean enabled;

    /** "roomId:userId" -> what we last knew about them. */
    private final Map<String, Tracked> tracked = new ConcurrentHashMap<>();
    private volatile boolean lastPassFailed;
    private ScheduledExecutorService worker;

    public RoomPresenceService(
            VoiceRoomParticipantRepository participants,
            VoiceRoomRepository rooms,
            RoomStateService roomState,
            LiveKitPermissionService liveKit,
            PlatformTransactionManager transactionManager,
            MeterRegistry metrics,
            @Value("${app.voice.grace-seconds:60}") long graceSeconds,
            @Value("${app.voice.presence-sweep-seconds:15}") long sweepSeconds,
            @Value("${app.voice.presence-sweep-enabled:true}") boolean enabled
    ) {
        this.participants = participants;
        this.rooms = rooms;
        this.roomState = roomState;
        this.liveKit = liveKit;
        this.tx = new TransactionTemplate(transactionManager);
        this.metrics = metrics;
        this.grace = Duration.ofSeconds(graceSeconds);
        this.sweepSeconds = sweepSeconds;
        this.enabled = enabled;
    }

    @PostConstruct
    void start() {
        if (!enabled) {
            log.info("Voice presence checks are off (app.voice.presence-sweep-enabled=false)");
            return;
        }
        worker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "voice-presence");
            t.setDaemon(true);
            return t;
        });
        worker.scheduleWithFixedDelay(this::sweep, sweepSeconds, sweepSeconds, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        if (worker != null) worker.shutdownNow();
    }

    /** They just asked to join or reconnect: give them a full grace period to connect. */
    public void touch(Long roomId, Long userId) {
        tracked.put(key(roomId, userId), new Tracked(State.CONNECTING, Instant.now()));
    }

    public String stateOf(Long roomId, Long userId) {
        Tracked t = tracked.get(key(roomId, userId));
        return (t == null ? State.CONNECTING : t.state()).name();
    }

    /** One check of every active room. */
    void sweep() {
        try {
            List<Long> roomIds = participants.findActiveRoomIdsWithParticipants();
            Set<String> stillJoined = new HashSet<>();
            for (Long roomId : roomIds) {
                Optional<List<LivekitModels.ParticipantInfo>> connected = liveKit.listParticipants(roomId);
                if (connected.isEmpty()) {
                    // LiveKit isn't answering: nobody is marked as gone, and the clocks restart when it's back
                    lastPassFailed = true;
                    return;
                }
                if (lastPassFailed) {
                    lastPassFailed = false;
                    Instant now = Instant.now();
                    tracked.replaceAll((k, t) -> t.state() == State.CONNECTED ? t : new Tracked(t.state(), now));
                }
                try {
                    checkRoom(roomId, connected.get(), stillJoined);
                } catch (Exception e) {
                    log.warn("Voice presence check failed. room={}: {}", roomId, e.getMessage());
                    // Don't forget anyone in a room we couldn't check
                    String prefix = roomId + ":";
                    tracked.keySet().stream().filter(k -> k.startsWith(prefix)).forEach(stillJoined::add);
                }
            }
            tracked.keySet().retainAll(stillJoined);
        } catch (Exception e) {
            log.warn("Voice presence pass failed: {}", e.getMessage());
        }
    }

    private void checkRoom(Long roomId, List<LivekitModels.ParticipantInfo> connected, Set<String> stillJoined) {
        Map<String, LivekitModels.ParticipantInfo> byIdentity = connected.stream()
                .filter(p -> p.getState() != LivekitModels.ParticipantInfo.State.DISCONNECTED)
                .collect(Collectors.toMap(LivekitModels.ParticipantInfo::getIdentity, p -> p, (a, b) -> b));
        Instant now = Instant.now();
        for (VoiceRoomParticipant p : participants.findByRoomIdAndStatus(roomId, "JOINED")) {
            Long userId = p.getUserId();
            String key = key(roomId, userId);
            if (byIdentity.containsKey(LiveKitPermissionService.identity(userId))) {
                tracked.put(key, new Tracked(State.CONNECTED, now));
                stillJoined.add(key);
                continue;
            }
            // Missing. Someone who was connected starts their grace period now;
            // someone connecting keeps the one from when they asked for a token.
            Tracked t = tracked.compute(key, (k, old) ->
                    old == null || old.state() == State.CONNECTED ? new Tracked(State.DISCONNECTED, now) : old);
            if (Duration.between(t.since(), now).compareTo(grace) >= 0 && markLeft(roomId, userId)) continue;
            stillJoined.add(key);
        }
        correctTalkingRights(roomId, byIdentity);
    }

    /** The grace period ran out. False when they came back in the meantime. */
    private boolean markLeft(Long roomId, Long userId) {
        String key = key(roomId, userId);
        Boolean left = tx.execute(status -> {
            // Same lock as joining: a join in progress finishes (and restarts the clock) first
            rooms.findByIdForUpdate(roomId).orElseThrow();
            Tracked t = tracked.get(key);
            if (t == null || t.state() == State.CONNECTED
                    || Duration.between(t.since(), Instant.now()).compareTo(grace) < 0) {
                return false;
            }
            Optional<VoiceRoomParticipant> row =
                    participants.findByRoomIdAndUserIdAndStatus(roomId, userId, "JOINED");
            row.ifPresent(p -> {
                p.setStatus("LEFT");
                p.setLeftAt(Instant.now());
                p.setMuted(false);
                participants.save(p);
            });
            return row.isPresent();
        });
        if (!Boolean.TRUE.equals(left)) return false;
        tracked.remove(key);
        roomState.onLeft(roomId, userId);
        metrics.counter("panchayat.voice.grace_expired").increment();
        log.info("Voice presence: room={} user={} left after the grace period", roomId, userId);
        return true;
    }

    private void correctTalkingRights(Long roomId, Map<String, LivekitModels.ParticipantInfo> byIdentity) {
        Map<Long, Boolean> desired = roomState.desiredPublishers(roomId);
        desired.forEach((userId, can) -> {
            LivekitModels.ParticipantInfo p = byIdentity.get(LiveKitPermissionService.identity(userId));
            if (p != null && p.getPermission().getCanPublish() != can) {
                liveKit.repair(roomId, userId, can);
            }
        });
    }

    private static String key(Long roomId, Long userId) {
        return roomId + ":" + userId;
    }
}
