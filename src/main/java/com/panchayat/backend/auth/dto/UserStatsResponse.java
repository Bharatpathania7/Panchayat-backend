package com.panchayat.backend.user.dto;

public record UserStatsResponse(
        int level,
        long expPoints,
        long charmPoints,
        long vipPoints
) {
}