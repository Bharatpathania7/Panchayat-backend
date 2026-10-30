package com.panchayat.backend.user.service;

import com.panchayat.backend.auth.entity.User;
import com.panchayat.backend.auth.repository.UserRepository;
import com.panchayat.backend.user.dto.UpdateProfileRequest;
import com.panchayat.backend.user.dto.PublicUserProfileResponse;
import com.panchayat.backend.user.dto.UserProfileResponse;
import com.panchayat.backend.user.dto.UserStatsResponse;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class UserProfileService {

    private final UserRepository userRepository;


    private static final Set<String> ALLOWED_GAMES = Set.of(
            "CHESS",
            "LUDO",
            "MIC_GRAB",
            "CARROM",
            "POOL",
            "DRAW_AND_GUESS",
            "ANTAKSHARI",
            "CARD_NIGHT"
    );





    public UserProfileService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public UserProfileResponse getMyProfile(String authenticatedEmail) {
        User user = findUserByEmail(authenticatedEmail);
        return toResponse(user);
    }

    @Transactional(readOnly = true)
    public PublicUserProfileResponse getPublicProfile(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() ->
                        new UserNotFoundException("User not found"));

        return toPublicResponse(user);
    }

    @Transactional
    public UserProfileResponse updateMyProfile(
            String authenticatedEmail,
            UpdateProfileRequest request
    ) {
        User user = findUserByEmail(authenticatedEmail);

        if (request.fullName() != null) {
            String value = request.fullName().trim();

            if (value.isEmpty()) {
                throw new IllegalArgumentException(
                        "Full name cannot be blank"
                );
            }

            user.setFullName(value);
        }

        if (request.gender() != null) {
            String value = request.gender().trim();

            if (value.isEmpty()) {
                throw new IllegalArgumentException(
                        "Gender cannot be blank"
                );
            }

            user.setGender(value);
        }

        if (request.dateOfBirth() != null) {
            LocalDate today = LocalDate.now();

            if (!request.dateOfBirth().isBefore(today)) {
                throw new IllegalArgumentException(
                        "Date of birth must be in the past"
                );
            }

            int age = Period.between(
                    request.dateOfBirth(),
                    today
            ).getYears();

            if (age < 18) {
                throw new IllegalArgumentException(
                        "You must be at least 18 years old"
                );
            }

            user.setDateOfBirth(request.dateOfBirth());
        }

        if (request.nameFont() != null) {
            user.setNameFont(request.nameFont());
        }

        if (request.bio() != null) {
            String bio = request.bio().trim();

            if (bio.length() > 120) {
                throw new IllegalArgumentException(
                        "Bio cannot exceed 120 characters"
                );
            }

            user.setBio(bio);
        }

        if (request.avatarBackground() != null) {
            user.setAvatarBackground(request.avatarBackground());
        }

        if (request.avatarSkinTone() != null) {
            user.setAvatarSkinTone(request.avatarSkinTone());
        }

        if (request.avatarOutfit() != null) {
            user.setAvatarOutfit(request.avatarOutfit());
        }

        if (request.avatarHair() != null) {
            user.setAvatarHair(request.avatarHair());
        }

        if (request.avatarUrl() != null) {
            user.setAvatarUrl(
                    validateImageUrl(request.avatarUrl(), "Avatar")
            );
        }

        if (request.bannerUrl() != null) {
            user.setBannerUrl(
                    validateImageUrl(request.bannerUrl(), "Banner")
            );
        }

        if (request.favoriteGames() != null) {
            List<String> games = request.favoriteGames()
                    .stream()
                    .map(game -> {
                        if (game == null || game.isBlank()) {
                            throw new IllegalArgumentException(
                                    "Favorite game cannot be blank"
                            );
                        }

                        return normalize(game);
                    })
                    .toList();

            if (games.size() > 4) {
                throw new IllegalArgumentException(
                        "You can select a maximum of 4 favorite games"
                );
            }

            if (games.stream().distinct().count() != games.size()) {
                throw new IllegalArgumentException(
                        "Duplicate favorite games are not allowed"
                );
            }

            if (!ALLOWED_GAMES.containsAll(games)) {
                throw new IllegalArgumentException(
                        "One or more favorite games are unsupported"
                );
            }

            user.setFavoriteGames(games);
        }

        User savedUser = userRepository.save(user);

        return toResponse(savedUser);
    }

    @Transactional(readOnly = true)
    public UserStatsResponse getMyStats(String authenticatedEmail) {
        User user = findUserByEmail(authenticatedEmail);

        return new UserStatsResponse(
                user.getLevel(),
                user.getExpPoints(),
                user.getCharmPoints(),
                user.getVipPoints()
        );
    }

    private User findUserByEmail(String email) {
        return userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() ->
                        new UserNotFoundException(
                                "Authenticated user not found"
                        ));
    }

    private String normalize(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String validateImageUrl(
            String input,
            String fieldName
    ) {
        String value = input.trim();

        if (value.isEmpty()) {
            throw new IllegalArgumentException(
                    fieldName + " URL cannot be blank"
            );
        }

        if (value.length() > 1000) {
            throw new IllegalArgumentException(
                    fieldName + " URL cannot exceed 1000 characters"
            );
        }

        try {
            URI uri = URI.create(value);

            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getHost().isBlank()) {
                throw new IllegalArgumentException(
                        fieldName + " URL must be a valid HTTPS URL"
                );
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    fieldName + " URL must be a valid HTTPS URL",
                    exception
            );
        }

        return value;
    }

    private UserProfileResponse toResponse(User user) {
        List<String> favoriteGames =
                new java.util.ArrayList<>(user.getFavoriteGames());

        return new UserProfileResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getGender(),
                user.getDateOfBirth(),
                user.isEmailVerified(),
                user.isProfileCompleted(),
                user.getCreatedAt(),
                user.getAvatarUrl(),
                user.getBannerUrl(),
                user.getNameFont(),
                user.getBio(),
                favoriteGames,
                user.getAvatarBackground(),
                user.getAvatarSkinTone(),
                user.getAvatarOutfit(),
                user.getAvatarHair()
        );
    }

    private PublicUserProfileResponse toPublicResponse(User user) {
        List<String> favoriteGames =
                new java.util.ArrayList<>(user.getFavoriteGames());

        return new PublicUserProfileResponse(
                user.getId(),
                user.getFullName(),
                user.getAvatarUrl(),
                user.getBannerUrl(),
                user.getNameFont(),
                user.getBio(),
                favoriteGames,
                user.getAvatarBackground(),
                user.getAvatarSkinTone(),
                user.getAvatarOutfit(),
                user.getAvatarHair()
        );
    }

    public static class UserNotFoundException
            extends RuntimeException {

        public UserNotFoundException(String message) {
            super(message);
        }
    }
}
