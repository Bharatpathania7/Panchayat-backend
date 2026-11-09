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
        String entryAnimationUrl,
        // Worn spend rewards: FRAME | BUBBLE | ENTRY | PROFILE -> { url, format, soundUrl, name }
        java.util.Map<String, java.util.Map<String, Object>> rewardEffects,
        // The 7-digit ID people see and search by
        Long publicId,
        // CONNECTING | CONNECTED | DISCONNECTED (still in the room, inside the grace period)
        String connectionState
) {
}
