package com.panchayat.backend.voice.dto;

import com.panchayat.backend.rooms.service.RoomSettingsJson;
import com.panchayat.backend.voice.entity.VoiceRoom;
import com.panchayat.backend.voice.entity.VoiceRoomType;

import java.time.Instant;
import java.util.Map;

/**
 * A room as everyone sees it. The password itself is never included
 * (hasPassword only); the owner and admins read it from /settings/private.
 */
public record VoiceRoomResponse(
        Long id,
        Long ownerId,
        String name,
        String description,
        VoiceRoomType roomType,
        boolean isPrivate,
        int maxParticipants,
        String status,
        Instant createdAt,
        String category,
        String coverTheme,
        String coverMotif,
        String coverImageUrl,
        String announcement,
        boolean hasPassword,
        Map<String, Object> settings,
        int memberCount,
        int fanCount,
        long points
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
                room.getCreatedAt(),
                room.getCategory(),
                room.getCoverTheme(),
                room.getCoverMotif(),
                room.getCoverImageUrl(),
                room.getAnnouncement(),
                room.getPassword() != null && !room.getPassword().isBlank(),
                RoomSettingsJson.read(room.getSettings()),
                Math.max(0, room.getMemberCount()),
                Math.max(0, room.getFanCount()),
                Math.max(0, room.getPoints())
        );
    }
}
