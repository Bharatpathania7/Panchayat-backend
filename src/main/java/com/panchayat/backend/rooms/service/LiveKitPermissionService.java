package com.panchayat.backend.rooms.service;

import io.livekit.server.RoomServiceClient;
import livekit.LivekitModels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import retrofit2.Response;

import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tells LiveKit who may publish audio. This is what really enforces the seat
 * rules: a client can't talk without the server granting canPublish, whatever
 * the app's UI does.
 *
 * Calls run on one background thread (in order), only when the permission
 * actually changes. A participant not connected to LiveKit yet is fine: their
 * join token already carries the right permission.
 */
@Service
public class LiveKitPermissionService {

    private static final Logger log =
            LoggerFactory.getLogger(LiveKitPermissionService.class);

    private final RoomServiceClient client;
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(r -> {
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

        worker.submit(() -> {
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

                if (!response.isSuccessful()) {
                    // 404: not connected to LiveKit right now (their token decides)
                    log.debug("LiveKit permission update not applied. room={}, user={}, code={}",
                            roomId, userId, response.code());
                }
            } catch (Exception e) {
                log.warn("LiveKit permission update failed. room={}, user={}: {}",
                        roomId, userId, e.getMessage());
            }
        });
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
