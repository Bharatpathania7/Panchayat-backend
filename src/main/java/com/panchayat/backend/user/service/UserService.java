
        package com.panchayat.backend.user.service;

import com.panchayat.backend.auth.entity.User;
import com.panchayat.backend.auth.repository.UserRepository;
import com.panchayat.backend.auth.service.VipLevelService;
import com.panchayat.backend.user.dto.UpdateProfileRequest;
import com.panchayat.backend.user.dto.UserProfileResponse;
import com.panchayat.backend.vip.entity.UserEquippedCosmetic;
import com.panchayat.backend.vip.entity.VipCosmetic;
import com.panchayat.backend.vip.repository.UserEquippedCosmeticRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

@Service
public class UserService {

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate giftJdbc;


    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private com.panchayat.backend.cp.CpService cpService;

    /** Effects each person wears from their Bag (spend rewards); everyone sees them. */
    @org.springframework.beans.factory.annotation.Autowired
    private com.panchayat.backend.rewards.service.SpendRewardService rewardBag;


    private final UserRepository userRepository;
    private final UserEquippedCosmeticRepository equippedCosmeticRepository;
    private final VipLevelService vipLevelService;

    public UserService(
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
        User user = findUser(authenticatedEmail);
        return toResponse(user);
    }

    @Transactional
    public UserProfileResponse updateMyProfile(
            String authenticatedEmail,
            UpdateProfileRequest request
    ) {
        User user = findUser(authenticatedEmail);

        if (request.fullName() != null) {
            String fullName = request.fullName().trim();

            if (fullName.isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Full name cannot be blank"
                );
            }

            user.setFullName(fullName);
        }

        if (request.gender() != null) {
            String gender = request.gender()
                    .trim()
                    .toUpperCase(Locale.ROOT);

            if (!List.of(
                    "MALE",
                    "FEMALE",
                    "OTHER",
                    "PREFER_NOT_TO_SAY"
            ).contains(gender)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Invalid gender"
                );
            }

            user.setGender(gender);
        }

        if (request.dateOfBirth() != null) {
            LocalDate dob = request.dateOfBirth();
            LocalDate today = LocalDate.now();

            if (!dob.isBefore(today)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Date of birth must be in the past"
                );
            }

            int age = Period.between(dob, today).getYears();

            if (age < 18) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "You must be at least 18 years old"
                );
            }

            user.setDateOfBirth(dob);
        }

        if (request.bio() != null) {
            String bio = request.bio().trim();

            if (bio.length() > 120) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Bio cannot exceed 120 characters"
                );
            }

            user.setBio(bio);
        }

        if (request.nameFont() != null) {
            user.setNameFont(request.nameFont().trim());
        }

        if (request.favoriteGames() != null) {
            List<String> games = request.favoriteGames()
                    .stream()
                    .map(game -> {
                        if (game == null || game.isBlank()) {
                            throw new ResponseStatusException(
                                    HttpStatus.BAD_REQUEST,
                                    "Favorite game cannot be blank"
                            );
                        }

                        return game.trim().toUpperCase(Locale.ROOT);
                    })
                    .toList();

            if (games.size() > 4) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "You can select a maximum of 4 favorite games"
                );
            }

            if (games.size() != new HashSet<>(games).size()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Duplicate favorite games are not allowed"
                );
            }

            List<String> allowedGames = List.of(
                    "CHESS",
                    "LUDO",
                    "MIC_GRAB",
                    "CARROM",
                    "POOL",
                    "DRAW_AND_GUESS",
                    "ANTAKSHARI",
                    "CARD_NIGHT"
            );

            if (!allowedGames.containsAll(games)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "One or more favorite games are unsupported"
                );
            }

            user.setFavoriteGames(games);
        }

        // Avatar and banner URLs remain managed by dedicated upload APIs.

        if (request.avatarBackground() != null) {
            user.setAvatarBackground(
                    request.avatarBackground().trim()
            );
        }

        if (request.avatarSkinTone() != null) {
            user.setAvatarSkinTone(
                    request.avatarSkinTone().trim()
            );
        }

        if (request.avatarOutfit() != null) {
            user.setAvatarOutfit(
                    request.avatarOutfit().trim()
            );
        }

        if (request.avatarHair() != null) {
            user.setAvatarHair(
                    request.avatarHair().trim()
            );
        }

        User savedUser = userRepository.save(user);

        return toResponse(savedUser);
    }

    private UserProfileResponse toResponse(User user) {
        List<String> favoriteGames = user.getFavoriteGames() == null
                ? new ArrayList<>()
                : new ArrayList<>(user.getFavoriteGames());

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
                com.panchayat.backend.common.PublicIds.user(user.getId()),
                cpService.summary(user.getId()),
                com.panchayat.backend.user.controller.GiftSetWearController.read(giftJdbc, user.getId())
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

            // Only allow cosmetics unlocked at the user's effective VIP level.
            if (cosmetic.getVipLevel() > vipLevel) {
                continue;
            }

            String url = cosmetic.getSecureUrl();

            if (url == null || url.isBlank()) {
                continue;
            }

            switch (cosmetic.getCosmeticType()) {
                case BADGE -> { } // the badge always follows the VIP level (below)
                case FRAME -> frameUrl = url;
                case BUBBLE -> bubbleUrl = url;
                case ENTRY -> { } // profiles don't carry the entry effect (rooms get it from the participant list)
            }
        }

        // The VIP badge is not worn by choice: it is always the badge of your current VIP level
        // (or the closest level below that has one uploaded)
        List<String> badge = giftJdbc.queryForList(
                "select secure_url from vip_cosmetics where cosmetic_type = 'BADGE' and vip_level <= ? and secure_url is not null order by vip_level desc limit 1",
                String.class, vipLevel);
        badgeUrl = badge.isEmpty() ? null : badge.get(0);

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

    private User findUser(String email) {
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Authentication is required"
            );
        }

        return userRepository.findByEmailIgnoreCase(email.trim())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "User not found"
                ));
    }

    private record VipDetails(
            boolean active,
            Integer level,
            Instant expiresAt,
            String badgeUrl,
            String frameUrl,
            String bubbleUrl,
            String entryAnimationUrl
    ) {
    }
}
