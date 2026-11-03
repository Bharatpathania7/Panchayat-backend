package com.panchayat.backend.user.service;

import com.panchayat.backend.auth.entity.User;
import com.panchayat.backend.auth.repository.UserRepository;
import com.panchayat.backend.user.dto.PublicUserProfileResponse;
import com.panchayat.backend.user.dto.UpdateProfileRequest;
import com.panchayat.backend.user.dto.UserProfileResponse;
import com.panchayat.backend.user.dto.UserStatsResponse;
import com.panchayat.backend.vip.entity.VipCosmetic;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.panchayat.backend.auth.service.VipLevelService;
import com.panchayat.backend.vip.entity.UserEquippedCosmetic;
import com.panchayat.backend.vip.repository.UserEquippedCosmeticRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;

@Service
public class UserProfileService {
    /** Effects each person wears from their Bag (spend rewards); everyone sees them. */
    @org.springframework.beans.factory.annotation.Autowired
    private com.panchayat.backend.rewards.service.SpendRewardService rewardBag;


    private final UserRepository userRepository;
    private final UserEquippedCosmeticRepository equippedCosmeticRepository;
    private final VipLevelService vipLevelService;

    public UserProfileService(
            UserRepository userRepository,
            UserEquippedCosmeticRepository equippedCosmeticRepository,
            VipLevelService vipLevelService
    ) {
        this.userRepository = userRepository;
        this.equippedCosmeticRepository = equippedCosmeticRepository;
        this.vipLevelService = vipLevelService;
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
                        "Full name cannot be blank");
            }

            user.setFullName(value);
        }

        if (request.gender() != null) {
            String value = request.gender().trim();

            if (value.isEmpty()) {
                throw new IllegalArgumentException(
                        "Gender cannot be blank");
            }

            user.setGender(value);
        }

        if (request.dateOfBirth() != null) {
            LocalDate today = LocalDate.now();

            if (!request.dateOfBirth().isBefore(today)) {
                throw new IllegalArgumentException(
                        "Date of birth must be in the past");
            }

            int age = Period.between(
                    request.dateOfBirth(),
                    today
            ).getYears();

            if (age < 18) {
                throw new IllegalArgumentException(
                        "You must be at least 18 years old");
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
                        "Bio cannot exceed 120 characters");
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

        // Avatar and banner images use dedicated Cloudinary upload APIs.

        if (request.favoriteGames() != null) {
            List<String> games =
                    new ArrayList<>(request.favoriteGames());

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
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException(
                    "Authenticated email cannot be blank");
        }

        return userRepository.findByEmailIgnoreCase(email.trim())
                .orElseThrow(() ->
                        new UserNotFoundException(
                                "Authenticated user not found"));
    }

    private UserProfileResponse toResponse(User user) {
        List<String> favoriteGames =
                new ArrayList<>(user.getFavoriteGames());

        VipDetails vip = getVipDetails(user);

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
                user.getAvatarHair(),

                vip.active(),
                vip.level(),
                vip.expiresAt(),
                vip.badgeUrl(),
                vip.frameUrl(),
                vip.bubbleUrl(),
                vip.entryAnimationUrl(),
                rewardBag.effectsFor(user.getId()),
                com.panchayat.backend.common.PublicIds.user(user.getId())
        );
    }

    private PublicUserProfileResponse toPublicResponse(User user) {
        List<String> favoriteGames =
                new ArrayList<>(user.getFavoriteGames());

        VipDetails vip = getVipDetails(user);

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
                user.getAvatarHair(),

                vip.active(),
                vip.level(),
                vip.expiresAt(),
                vip.badgeUrl(),
                vip.frameUrl(),
                vip.bubbleUrl(),
                vip.entryAnimationUrl(),
                rewardBag.effectsFor(user.getId()),
                com.panchayat.backend.common.PublicIds.user(user.getId())
        );
    }

    private VipDetails getVipDetails(User user) {

        Instant expiresAt = user.getVipExpiresAt();

        boolean active = vipLevelService.isVipMembershipActive(user);

        if (!active) {
            return new VipDetails(
                    false,
                    null,
                    expiresAt,
                    null,
                    null,
                    null,
                    null
            );
        }

        int vipLevel = vipLevelService.calculateEffectiveVipLevel(user);

        List<UserEquippedCosmetic> equipped =
                equippedCosmeticRepository.findAllByUser_Id(user.getId());

        String badgeUrl = null;
        String frameUrl = null;
        String bubbleUrl = null;
        String entryAnimationUrl = null;

        for (UserEquippedCosmetic item : equipped) {

            VipCosmetic cosmetic = item.getCosmetic();

            if (cosmetic == null) {
                continue;
            }

            // A user's VIP level must still allow this cosmetic.
            if (cosmetic.getVipLevel() > vipLevel) {
                continue;
            }

            String url = cosmetic.getSecureUrl();

            if (url == null || url.isBlank()) {
                continue;
            }

            switch (cosmetic.getCosmeticType()) {
                case BADGE -> badgeUrl = url;
                case FRAME -> frameUrl = url;
                case BUBBLE -> bubbleUrl = url;
                case ENTRY -> entryAnimationUrl = url;
            }
        }

        return new VipDetails(
                true,
                vipLevel,
                expiresAt,
                badgeUrl,
                frameUrl,
                bubbleUrl,
                entryAnimationUrl
        );
    }

    private record VipDetails(
            boolean active,
            Integer level,
            Instant expiresAt,
            String badgeUrl,
            String frameUrl,
            String bubbleUrl,
            String entryAnimationUrl
    ) {}

    public static class UserNotFoundException
            extends RuntimeException {

        public UserNotFoundException(String message) {
            super(message);
        }
    }
}
