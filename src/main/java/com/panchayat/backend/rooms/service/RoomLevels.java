package com.panchayat.backend.rooms.service;

/**
 * Voice room levels 1 to 10, from room EXP. Each level raises how many members and admins the room can have
 * and unlocks more (co-owners, recommendation cards, room backgrounds). The app shows the same table.
 */
public final class RoomLevels {

    private RoomLevels() {
    }

    /** EXP needed for each level. */
    public static final long[] EXP = {0, 8_000, 30_000, 100_000, 300_000, 800_000, 2_000_000, 4_800_000, 11_000_000, 24_000_000};
    public static final int[] MEMBERS = {1_000, 2_000, 3_000, 5_000, 7_000, 10_000, 20_000, 30_000, 40_000, 50_000};
    public static final int[] ADMINS = {3, 4, 5, 6, 7, 8, 9, 10, 12, 15};
    public static final int[] CO_OWNERS = {0, 0, 0, 0, 1, 1, 1, 2, 2, 2};
    public static final int[] RECOMMENDATION_CARDS = {2, 2, 2, 2, 3, 3, 4, 4, 5, 5};
    /** The room background each level unlocks (empty = none). */
    public static final String[] BACKGROUND = {"", "", "Club", "", "Diamonden Party", "", "Stars of a different world", "", "Future City", "Custom background"};

    /** EXP a room can earn each day: from time spent in it, from gifts and red packets, and in all. */
    public static final long DAILY_VOICE_CAP = 5_000;
    public static final long DAILY_GIFT_CAP = 50_000;
    public static final long DAILY_TOTAL_CAP = 55_000;
    /** Each person in the room for a minute adds this much EXP (double for VIP); 5 minutes = 5 EXP. */
    public static final long VOICE_EXP_PER_MINUTE = 1;
    /** 10 coins sent as a gift = 1 EXP. */
    public static final long COINS_PER_EXP = 10;

    public static int level(long exp) {
        int lv = 1;
        for (int i = 0; i < EXP.length; i++) if (exp >= EXP[i]) lv = i + 1;
        return lv;
    }

    public static Long nextExp(long exp) {
        int lv = level(exp);
        return lv >= EXP.length ? null : EXP[lv];
    }

    public static int adminLimit(long exp) {
        return ADMINS[level(exp) - 1];
    }
}
