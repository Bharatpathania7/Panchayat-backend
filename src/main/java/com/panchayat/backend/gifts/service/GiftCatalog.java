package com.panchayat.backend.gifts.service;

import java.util.Map;
import java.util.Optional;

/**
 * Prices of the gifts on sale. The admin panel manages them (table gifts);
 * GiftCatalogService loads them at startup and after every admin change, so a
 * new price applies to the next send. The app never chooses the price.
 */
public final class GiftCatalog {

    /** Used only until the database has been read at startup. */
    private static final Map<String, Long> DEFAULTS = Map.ofEntries(
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

    private static volatile Map<String, Long> prices = DEFAULTS;

    /** The receiver gets this share of a gift's value back as coins. */
    public static final int RECEIVER_SHARE_PERCENT = 30;

    /** Room points per coin gifted in the room. */
    public static final long ROOM_POINTS_PER_COIN = 100;

    private GiftCatalog() {
    }

    /** Price of a gift that's on sale (active), or empty. */
    public static Optional<Long> price(String giftId) {
        return Optional.ofNullable(giftId == null ? null : prices.get(giftId));
    }

    private static volatile java.util.Set<String> shopOnly = java.util.Set.of();

    /** A Shop-only gift: can only be sent from the Package (bought in the Shop), never bought with coins in the gift panel. */
    public static boolean isShopOnly(String giftId) {
        return giftId != null && shopOnly.contains(giftId);
    }

    static void replaceShopOnly(java.util.Set<String> ids) {
        shopOnly = java.util.Set.copyOf(ids);
    }

    /** Active gifts and their prices, from the database. */
    static void replace(Map<String, Long> active) {
        prices = Map.copyOf(active);
    }
}
