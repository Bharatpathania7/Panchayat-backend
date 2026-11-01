package com.panchayat.backend.voice.dto;

public record JoinVoiceRoomResponse(
        String serverUrl,
        String token,
        String roomName,
        long expiresInSeconds
) {
}