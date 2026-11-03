package com.panchayat.backend.gifts.service;

import java.util.Map;
import java.util.Optional;

/**
 * Gifts on sale and their prices in coins. The server's prices are the ones
 * charged; the app shows the same list (bharatxaryan/lib/app/data.ts GIFTS).
 * Change both together.
 */
public final class GiftCatalog {

    private static final Map<String, Long> PRICES = Map.ofEntries(
            Map.entry("cupid-heart", 18_888L),
            Map.entry("sunset-romance", 52_013L),
            Map.entry("dragon", 19_999L),
            Map.entry("princess-flower", 39_999L),
            Map.entry("love-fireworks", 13_140L),
            Map.entry("god-of-wealth", 18_888L),
            Map.entry("city-fireworks", 2_000L),
            Map.entry("neon-racer", 9_999L),
            Map.entry("star-prince", 19_999L),
            Map.entry("savanna-king", 39_999L),
            Map.entry("crystal-palace", 131_420L),
            Map.entry("storm-guardian", 131_420L)
    );

    /** The receiver gets this share of a gift's value back as coins. */
    public static final int RECEIVER_SHARE_PERCENT = 30;

    /** Room points per coin gifted in the room. */
    public static final long ROOM_POINTS_PER_COIN = 100;

    private GiftCatalog() {
    }

    public static Optional<Long> price(String giftId) {
        return Optional.ofNullable(giftId == null ? null : PRICES.get(giftId));
    }
}
