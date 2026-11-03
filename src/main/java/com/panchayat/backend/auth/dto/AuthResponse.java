package com.panchayat.backend.auth.dto;

/**
 * accessToken: short-lived JWT for API calls (expiresIn seconds).
 * refreshToken: long-lived; swap it at POST /api/v1/auth/refresh before the
 * access token runs out. Each refresh returns a new refreshToken.
 */
public record AuthResponse(
        String message,
        String accessToken,
        String tokenType,
        boolean profileCompleted,
        String refreshToken,
        long expiresIn
) {}
