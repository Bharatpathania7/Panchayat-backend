package com.panchayat.backend.voice.dto;

import java.time.Instant;

public record VoiceRoomParticipantResponse(
        Long userId,
        String name,
        String avatarUrl,
        String role,
        boolean muted,
        Instant joinedAt,
        Integer vipLevel,
        String badgeUrl,
        String frameUrl,
        String bubbleUrl,
        String entryAnimationUrl
) {
}
