package com.panchayat.backend.auth.dto;

public record AuthResponse(
        String message,
        String accessToken,
        String tokenType,
        boolean profileCompleted
) {}