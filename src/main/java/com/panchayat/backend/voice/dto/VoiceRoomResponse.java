package com.panchayat.backend.voice.dto;

import com.panchayat.backend.voice.entity.VoiceRoom;
import com.panchayat.backend.voice.entity.VoiceRoomType;
import java.time.Instant;

public record VoiceRoomResponse(
        Long id,
        Long ownerId,
        String name,
        String description,
        VoiceRoomType roomType,
        boolean isPrivate,
        int maxParticipants,
        String status,
        Instant createdAt
) {

    public static VoiceRoomResponse from(VoiceRoom room) {
        return new VoiceRoomResponse(
                room.getId(),
                room.getOwnerId(),
                room.getName(),
                room.getDescription(),
                room.getRoomType(),
                room.isPrivate(),
                room.getMaxParticipants(),
                room.getStatus(),
                room.getCreatedAt()
        );
    }
}