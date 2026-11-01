package com.panchayat.backend.voice.service;

import io.livekit.server.AccessToken;
import io.livekit.server.CanPublish;
import io.livekit.server.CanSubscribe;
import io.livekit.server.RoomJoin;
import io.livekit.server.RoomName;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class LiveKitTokenService {

    private static final long TOKEN_TTL_MILLIS = 10 * 60 * 1000L;

    private final String apiKey;
    private final String apiSecret;

    public LiveKitTokenService(
            @Value("${livekit.api-key}") String apiKey,
            @Value("${livekit.api-secret}") String apiSecret) {

        if (apiKey == null || apiKey.isBlank()
                || apiSecret == null || apiSecret.isBlank()) {
            throw new IllegalStateException(
                    "LiveKit credentials are not configured"
            );
        }

        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
    }

    /**
     * Generate a short-lived LiveKit token for a participant.
     */
    public String generateJoinToken(
            String roomName,
            String userIdentity) {

        if (roomName == null || roomName.isBlank()
                || userIdentity == null || userIdentity.isBlank()) {
            throw new IllegalArgumentException(
                    "Room name and user identity are required"
            );
        }

        AccessToken token = new AccessToken(apiKey, apiSecret);

        token.setIdentity(userIdentity);
        token.setTtl(TOKEN_TTL_MILLIS);

        token.addGrants(
                new RoomJoin(true),
                new RoomName(roomName),
                new CanPublish(true),
                new CanSubscribe(true)
        );

        return token.toJwt();
    }
}