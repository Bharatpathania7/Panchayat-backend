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
        String avatarHair
) {
}