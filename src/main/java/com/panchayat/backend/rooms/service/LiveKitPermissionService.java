package com.panchayat.backend.rooms.service;

import io.livekit.server.RoomServiceClient;
import livekit.LivekitModels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import retrofit2.Response;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Tells LiveKit who may publish audio. This is what really enforces the seat
 * rules: a client can't talk without the server granting canPublish, whatever
 * the app's UI does.
 *
 * Calls run on one background thread (in order), only when the permission
 * actually changes. Someone who hasn't connected to LiveKit yet can't be
 * updated, so a refused update is tried again a few times; RoomPresenceService
 * corrects anything still wrong on its next pass.
 */
@Service
public class LiveKitPermissionService {

    private static final Logger log =
            LoggerFactory.getLogger(LiveKitPermissionService.class);

    private final RoomServiceClient client;
    /** Seconds before each further try of a permission LiveKit didn't apply. */
    private static final int[] RETRY_DELAYS = {1, 3, 6};

    private final ScheduledExecutorService worker =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "livekit-permissions");
                t.setDaemon(true);
                return t;
            });

    /** "roomId:userId" -> last canPublish sent. */
    private final Map<String, Boolean> lastSent = new ConcurrentHashMap<>();

    public LiveKitPermissionService(
            @Value("${livekit.url}") String liveKitUrl,
            @Value("${livekit.api-key}") String apiKey,
            @Value("${livekit.api-secret}") String apiSecret
    ) {
        this.client = RoomServiceClient.create(liveKitUrl, apiKey, apiSecret);
    }

    public static String roomName(Long roomId) {
        return "panchayat-room-" + roomId;
    }

    public static String identity(Long userId) {
        return "user-" + userId;
    }

    /** Remember what the join token granted, so later changes are detected. */
    public void rememberTokenGrant(Long roomId, Long userId, boolean canPublish) {
        lastSent.put(roomId + ":" + userId, canPublish);
    }

    public void setCanPublish(Long roomId, Long userId, boolean canPublish) {
        String key = roomId + ":" + userId;
        Boolean previous = lastSent.put(key, canPublish);
        if (previous != null && previous == canPublish) {
            return;
        }

        worker.submit(() -> send(roomId, userId, canPublish, 0));
    }

    /** Sends the permission even if it was sent before: LiveKit says it isn't in force. */
    public void repair(Long roomId, Long userId, boolean canPublish) {
        lastSent.put(roomId + ":" + userId, canPublish);
        worker.submit(() -> send(roomId, userId, canPublish, RETRY_DELAYS.length));
    }

    private void send(Long roomId, Long userId, boolean canPublish, int attempt) {
        // A newer change, or they left: this one no longer matters
        if (!Objects.equals(lastSent.get(roomId + ":" + userId), canPublish)) return;
        try {
            LivekitModels.ParticipantPermission permission =
                    LivekitModels.ParticipantPermission.newBuilder()
                            .setCanSubscribe(true)
                            .setCanPublishData(true)
                            .setCanPublish(canPublish)
                            .build();

            Response<?> response = client.updateParticipant(
                    roomName(roomId),
                    identity(userId),
                    null,
                    null,
                    permission
            ).execute();

            if (response.isSuccessful()) return;
            // 404: not connected to LiveKit yet, or between connections
            log.debug("LiveKit permission update not applied. room={}, user={}, code={}",
                    roomId, userId, response.code());
        } catch (Exception e) {
            log.warn("LiveKit permission update failed. room={}, user={}: {}",
                    roomId, userId, e.getMessage());
        }
        if (attempt < RETRY_DELAYS.length) {
            worker.schedule(() -> send(roomId, userId, canPublish, attempt + 1),
                    RETRY_DELAYS[attempt], TimeUnit.SECONDS);
        }
    }

    /**
     * Who LiveKit has in a room right now. Empty when it couldn't answer, which
     * must never be read as "nobody is there". A room it doesn't know is an empty list.
     */
    public Optional<List<LivekitModels.ParticipantInfo>> listParticipants(Long roomId) {
        try {
            Response<List<LivekitModels.ParticipantInfo>> response =
                    client.listParticipants(roomName(roomId)).execute();
            if (response.isSuccessful()) {
                return Optional.of(response.body() == null ? List.of() : response.body());
            }
            if (response.code() == 404) return Optional.of(List.of());
            log.warn("LiveKit participant list refused. room={}, code={}", roomId, response.code());
        } catch (IOException e) {
            log.warn("LiveKit participant list failed. room={}: {}", roomId, e.getMessage());
        }
        return Optional.empty();
    }

    /** Disconnects someone from the call (after a kick or ban). */
    public void removeFromCall(Long roomId, Long userId) {
        lastSent.remove(roomId + ":" + userId);
        worker.submit(() -> {
            try {
                client.removeParticipant(roomName(roomId), identity(userId)).execute();
            } catch (Exception e) {
                log.warn("LiveKit remove participant failed. room={}, user={}: {}",
                        roomId, userId, e.getMessage());
            }
        });
    }

    public void forget(Long roomId, Long userId) {
        lastSent.remove(roomId + ":" + userId);
    }

    @PreDestroy
    void shutdown() {
        worker.shutdownNow();
    }
}
