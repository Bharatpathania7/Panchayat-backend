package com.panchayat.backend.auth.service;

import com.panchayat.backend.auth.entity.User;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class VipLevelService {

    private static final int MIN_VIP_LEVEL = 1;
    private static final int MAX_VIP_LEVEL = 11;

    private static final long[] VIP_POINT_THRESHOLDS = {
            0L,          // VIP 1
            1_000L,     // VIP 2
            5_000L,     // VIP 3
            25_000L,    // VIP 4
            60_000L,    // VIP 5
            140_000L,   // VIP 6
            300_000L,   // VIP 7
            600_000L,   // VIP 8
            1_080_000L, // VIP 9
            2_000_000L, // VIP 10
            4_800_000L  // VIP 11
    };

    /**
     * Calculates the VIP level earned through VIP points.
     */
    public int calculatePointsBasedLevel(long vipPoints) {

        if (vipPoints < 0) {
            vipPoints = 0;
        }

        int calculatedLevel = MIN_VIP_LEVEL;

        for (int i = 0; i < VIP_POINT_THRESHOLDS.length; i++) {
            if (vipPoints >= VIP_POINT_THRESHOLDS[i]) {
                calculatedLevel = i + 1;
            } else {
                break;
            }
        }

        return calculatedLevel;
    }

    /**
     * Returns the effective VIP level.
     * The higher of the points-based level and admin-assigned level wins.
     */
    public int calculateEffectiveVipLevel(User user) {

        int pointsBasedLevel =
                calculatePointsBasedLevel(user.getVipPoints());

        Integer adminLevel = user.getAdminVipLevel();

        if (adminLevel == null) {
            return pointsBasedLevel;
        }

        int validatedAdminLevel = Math.max(
                MIN_VIP_LEVEL,
                Math.min(MAX_VIP_LEVEL, adminLevel)
        );

        return Math.max(pointsBasedLevel, validatedAdminLevel);
    }

    /**
     * Checks whether the VIP membership is currently active.
     * A null expiry is treated as inactive.
     */
    public boolean isVipMembershipActive(User user) {

        return user.isVipActive()
                && user.getVipExpiresAt() != null
                && user.getVipExpiresAt().isAfter(Instant.now());
    }

    /**
     * Checks whether the user can currently access VIP cosmetics.
     */
    public boolean canAccessVipCosmetics(User user) {
        return isVipMembershipActive(user);
    }

    /**
     * Checks whether a cosmetic's VIP level is unlocked.
     */
    public boolean canAccessCosmetic(
            User user,
            int cosmeticVipLevel
    ) {

        if (!canAccessVipCosmetics(user)) {
            return false;
        }

        if (cosmeticVipLevel < MIN_VIP_LEVEL
                || cosmeticVipLevel > MAX_VIP_LEVEL) {
            return false;
        }

        return cosmeticVipLevel <= calculateEffectiveVipLevel(user);
    }
}