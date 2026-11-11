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
        long points,
        /* Room EXP and the level it gives (1 to 10). */
        long exp,
        int level,
        /* The 7-digit room ID people share and search by. */
        Long roomCode,
        /* The owner's 7-digit public user ID. */
        Long ownerPublicId,
        /* Newbie Welcome Room, and whether its 6 extra seats are open. */
        boolean newbie,
        boolean extraSeats,
        String newbieBackground
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
                Math.max(0, room.getPoints()),
                Math.max(0, room.getExp()),
                com.panchayat.backend.rooms.service.RoomLevels.level(Math.max(0, room.getExp())),
                com.panchayat.backend.common.PublicIds.room(room.getId()),
                com.panchayat.backend.common.PublicIds.user(room.getOwnerId()),
                room.isNewbie(),
                room.isExtraSeats(),
                room.isNewbie() ? com.panchayat.backend.admin.media.MediaUrls.link(room.getNewbieBackground()) : null
        );
    }
}
