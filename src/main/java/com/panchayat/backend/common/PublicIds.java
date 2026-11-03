package com.panchayat.backend.common;

/**
 * The 7-digit IDs people see, share and search by. Same formulas as the
 * generated columns users.public_id and voice_rooms.room_code (migration V26)
 * and the app (lib/app/public-ids.ts): change all three together, never one.
 */
public final class PublicIds {

    private static final long SPAN = 9_000_000L;

    private PublicIds() {
    }

    public static Long user(Long id) {
        return id == null ? null : 1_000_000L + Math.floorMod(id * 7_654_321L + 3_141_592L, SPAN);
    }

    public static Long room(Long id) {
        return id == null ? null : 1_000_000L + Math.floorMod(id * 4_782_961L + 7_777_777L, SPAN);
    }

    /** A 7-digit number someone typed, or null. */
    public static Long parse(String q) {
        if (q == null) return null;
        String t = q.trim().replaceAll("[\\s#]", "");
        if (!t.matches("\\d{7}")) return null;
        return Long.parseLong(t);
    }
}
