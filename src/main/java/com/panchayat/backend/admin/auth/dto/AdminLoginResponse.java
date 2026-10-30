package com.panchayat.backend.admin.auth.dto;



public record AdminLoginResponse(
        boolean success,
        String message,
        String tokenType,
        String accessToken,
        AdminUser user
) {
    public record AdminUser(
            Long id,
            String username,
            String role
    ) {
    }
}