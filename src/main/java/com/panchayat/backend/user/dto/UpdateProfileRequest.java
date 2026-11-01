package com.panchayat.backend.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

public record UpdateProfileRequest(

        @Size(max = 100, message = "Full name cannot exceed 100 characters")
        String fullName,

        @Size(max = 20, message = "Gender cannot exceed 20 characters")
        String gender,

        @Past(message = "Date of birth must be in the past")
        LocalDate dateOfBirth,

        @Size(max = 120, message = "Bio cannot exceed 120 characters")
        String bio,

        @Size(max = 30, message = "Name font cannot exceed 30 characters")
        String nameFont,

        @Size(max = 4, message = "You can select a maximum of 4 games")
        List<
                @NotBlank(message = "Favorite game cannot be blank")
                @Size(max = 30, message = "Game name cannot exceed 30 characters")
                        String
                > favoriteGames,

        @Size(max = 30, message = "Avatar background cannot exceed 30 characters")
        String avatarBackground,

        @Size(max = 30, message = "Avatar skin tone cannot exceed 30 characters")
        String avatarSkinTone,

        @Size(max = 30, message = "Avatar outfit cannot exceed 30 characters")
        String avatarOutfit,

        @Size(max = 30, message = "Avatar hair cannot exceed 30 characters")
        String avatarHair

) {}