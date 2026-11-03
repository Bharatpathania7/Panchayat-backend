package com.panchayat.backend.security;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fixed-window request counters kept in memory (one backend instance).
 * hit(key, limit, windowMs) answers 0 when allowed, or the seconds to wait.
 * Old windows are swept now and then so memory stays bounded under floods.
 */
@Component
public class RateLimiter {

    private static final int MAX_KEYS = 300_000;

    private record Window(long start, long windowMs, AtomicInteger count) {
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final AtomicLong calls = new AtomicLong();

    /** 0 = allowed; otherwise how many seconds until the window resets. */
    public long hit(String key, int limit, long windowMs) {
        long now = System.currentTimeMillis();
        if (calls.incrementAndGet() % 5_000 == 0 || windows.size() > MAX_KEYS) sweep(now);
        Window w = windows.compute(key, (k, old) ->
                old == null || now - old.start() >= old.windowMs() ? new Window(now, windowMs, new AtomicInteger()) : old);
        int n = w.count().incrementAndGet();
        if (n <= limit) return 0;
        return Math.max(1, (w.start() + w.windowMs() - now + 999) / 1000);
    }

    private void sweep(long now) {
        windows.entrySet().removeIf(e -> now - e.getValue().start() >= e.getValue().windowMs());
        // Still too many (a flood of distinct keys): start over rather than run out of memory
        if (windows.size() > MAX_KEYS) windows.clear();
    }
}
