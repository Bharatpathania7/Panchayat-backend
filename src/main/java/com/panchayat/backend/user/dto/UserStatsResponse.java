package com.panchayat.backend.user.dto;

/**
 * Your level and points. Level and EXP come from users.exp (what ExpService adds):
 * expInLevel of expToNext is how far you are into the current level.
 */
public record UserStatsResponse(
        int level,
        long expPoints,
        long charmPoints,
        long vipPoints,
        long expInLevel,
        long expToNext
) {
}
