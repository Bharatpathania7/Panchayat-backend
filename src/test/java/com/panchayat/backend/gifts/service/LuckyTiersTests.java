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
        assertTrue(low * 100.0 / n > 95.0, "low " + low);
        assertTrue(mid * 100.0 / n > 1.5 && mid * 100.0 / n < 3.5, "mid " + mid);
        assertTrue(high * 100.0 / n > 0.6 && high * 100.0 / n < 1.4, "high " + high);
    }

    @Test
    void aSingleValueRangeIsThatValue() {
        assertEquals(500, GiftLuckService.tiered(new Random(1), 500, 500));
    }
}
