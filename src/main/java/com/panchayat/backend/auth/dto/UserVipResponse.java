package com.panchayat.backend.auth.dto;

import com.panchayat.backend.auth.entity.User;

import java.time.Instant;

public record UserVipResponse(
        boolean active,
        Instant expiresAt,
        long vipPoints
) {
    public static UserVipResponse from(User user) {
        Instant now = Instant.now();

        boolean active = user.isVipActive()
                && user.getVipExpiresAt() != null
                && user.getVipExpiresAt().isAfter(now);

        return new UserVipResponse(
                active,
                user.getVipExpiresAt(),
                user.getVipPoints()
        );
    }
}