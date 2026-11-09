package com.panchayat.backend.gifts.service;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LuckyTiersTests {

    @Test
    void mostWinsAreSmallAndTheMaxIsRare() {
        Random r = new Random(7);
        long min = 60_000, max = 200_000, third = (max - min) / 3;
        int n = 200_000, low = 0, mid = 0, high = 0;
        for (int i = 0; i < n; i++) {
            long v = GiftLuckService.tiered(r, min, max);
            assertTrue(v >= min && v <= max);
            if (v <= min + third) low++;
            else if (v <= min + 2 * third) mid++;
            else high++;
        }
        assertTrue(low * 100.0 / n > 97.0 && low * 100.0 / n < 99.0, "low " + low);
        assertEquals(0, mid, "mid " + mid);
        assertTrue(high * 100.0 / n > 1.5 && high * 100.0 / n < 2.5, "high " + high);
    }

    @Test
    void coinsDifferFromGiftToGift() {
        Random r = new Random(11);
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (int i = 0; i < 200; i++) seen.add(GiftLuckService.tiered(r, 60_000, 200_000));
        assertTrue(seen.size() > 150, "only " + seen.size() + " different amounts in 200 gifts");
    }

    @Test
    void aSingleValueRangeIsThatValue() {
        assertEquals(500, GiftLuckService.tiered(new Random(1), 500, 500));
    }
}
