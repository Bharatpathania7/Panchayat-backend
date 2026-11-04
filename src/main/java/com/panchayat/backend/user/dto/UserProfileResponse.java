
        package com.panchayat.backend.user.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record UserProfileResponse(
        Long id,
        String email,
        String fullName,
        String gender,
        LocalDate dateOfBirth,
        boolean emailVerified,
        boolean profileCompleted,
        Instant createdAt,

        String avatarUrl,
        String bannerUrl,
        String nameFont,
        String bio,
        List<String> favoriteGames,

        String avatarBackground,
        String avatarSkinTone,
        String avatarOutfit,
        String avatarHair,

        // VIP details
        boolean vipActive,
        Integer vipLevel,
        Instant vipExpiresAt,

        // VIP cosmetics
        String badgeUrl,
        String frameUrl,
        String bubbleUrl,
        String entryAnimationUrl,

        // Worn spend rewards: FRAME | BUBBLE | ENTRY | PROFILE -> { url, format, soundUrl, name }
        java.util.Map<String, java.util.Map<String, Object>> rewardEffects,

        // The 7-digit ID people see and search by
        Long publicId,

        // CP: { coupleId, partnerUserId, partnerName, partnerAvatarUrl, ringId, blessings } or null
        java.util.Map<String, Object> cp,

        // Gift-set pieces you wear: { frame?, bubble?, entry?, page? } → set id
        java.util.Map<String, Object> giftSetWear
) {
}
