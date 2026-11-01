package com.panchayat.backend.auth.dto;

import com.panchayat.backend.auth.entity.User;

import java.time.Instant;

public record AdminVipResponse(
        Long userId,
        boolean vipActive,
        Instant vipExpiresAt,
        long vipPoints,
        Integer vipLevel,
        Integer adminVipLevel
) {
    public static AdminVipResponse from(User user) {

        boolean currentlyActive = user.isVipActive()
                && user.getVipExpiresAt() != null
                && user.getVipExpiresAt().isAfter(Instant.now());

        return new AdminVipResponse(
                user.getId(),
                currentlyActive,
                user.getVipExpiresAt(),
                user.getVipPoints(),
                user.getVipLevel(),
                user.getAdminVipLevel()
        );
    }
}