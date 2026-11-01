
        package com.panchayat.backend.user.dto;

import java.time.Instant;
import java.util.List;

public record PublicUserProfileResponse(
        Long id,
        String fullName,
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
        String entryAnimationUrl
) {}