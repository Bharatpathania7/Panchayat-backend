package com.panchayat.backend.cache;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ReadCacheTests {

    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final JsonMapper mapper = JsonMapper.builder().build();

    @SuppressWarnings("unchecked")
    private ReadCache cache(boolean enabled, boolean redisOn, StringRedisTemplate redis) {
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redis);
        return new ReadCache(mapper, metrics, provider, enabled, redisOn);
    }

    @Test
    void inMemory_oneLoadPerKeyWithinTheTimeToLive() {
        ReadCache c = cache(true, false, null);
        AtomicInteger loads = new AtomicInteger();
        for (int i = 0; i < 5; i++) assertEquals("a", c.json("gifts", "k", Duration.ofSeconds(30), () -> { loads.incrementAndGet(); return "a"; }));
        assertEquals("b", c.json("gifts", "other", Duration.ofSeconds(30), () -> "b"));
        assertEquals(1, loads.get());
        assertEquals(4.0, metrics.get("panchayat.cache").tag("result", "hit").counter().count());
        assertEquals(2.0, metrics.get("panchayat.cache").tag("result", "miss").counter().count());
    }

    @Test
    void inMemory_loadsAgainAfterTheTimeToLive() throws Exception {
        ReadCache c = cache(true, false, null);
        AtomicInteger loads = new AtomicInteger();
        c.json("x", "k", Duration.ofMillis(100), () -> "v" + loads.incrementAndGet());
        Thread.sleep(300);
        assertEquals("v2", c.json("x", "k", Duration.ofMillis(100), () -> "v" + loads.incrementAndGet()));
    }

    @Test
    void aFailingLoaderIsNeverCached() {
        ReadCache c = cache(true, false, null);
        assertThrows(IllegalStateException.class, () -> c.json("x", "bad", Duration.ofSeconds(30), () -> { throw new IllegalStateException("400"); }));
        assertEquals("ok", c.json("x", "bad", Duration.ofSeconds(30), () -> "ok"));
    }

    @Test
    void switchedOffAlwaysAsksTheDatabase() {
        ReadCache c = cache(false, false, null);
        AtomicInteger loads = new AtomicInteger();
        c.json("x", "k", Duration.ofSeconds(30), () -> "v" + loads.incrementAndGet());
        c.json("x", "k", Duration.ofSeconds(30), () -> "v" + loads.incrementAndGet());
        assertEquals(2, loads.get());
    }

    @Test
    void evictForgetsEverythingUnderAName() {
        ReadCache c = cache(true, false, null);
        AtomicInteger loads = new AtomicInteger();
        c.json("rings", "all", Duration.ofSeconds(30), () -> "v" + loads.incrementAndGet());
        c.evict("rings");
        assertEquals("v2", c.json("rings", "all", Duration.ofSeconds(30), () -> "v" + loads.incrementAndGet()));
    }

    @Test
    void respondGivesJsonTheAppCanRead() {
        ReadCache c = cache(true, false, null);
        ResponseEntity<String> r = c.respond("gifts", "k", Duration.ofSeconds(30), () -> List.of(Map.of("id", "rose", "price", 10)));
        assertEquals(MediaType.APPLICATION_JSON, r.getHeaders().getContentType());
        assertEquals("[{\"id\":\"rose\",\"price\":10}]", r.getBody());
    }

    @Test
    void loadGivesBackTheSameDataToWorkWith() {
        ReadCache c = cache(true, false, null);
        List<Map<String, Object>> rows = c.load("rankings", "charm|today", Duration.ofSeconds(30),
                new TypeReference<List<Map<String, Object>>>() { },
                () -> List.of(Map.of("userId", 7L, "value", 5L)));
        assertEquals(7L, ((Number) rows.get(0).get("userId")).longValue());
    }

    // ---------------- Redis (mocked: no Redis server is needed) ----------------

    @SuppressWarnings("unchecked")
    private static StringRedisTemplate redisWith(ValueOperations<String, String> ops) {
        StringRedisTemplate t = mock(StringRedisTemplate.class);
        when(t.opsForValue()).thenReturn(ops);
        return t;
    }

    @Test
    @SuppressWarnings("unchecked")
    void redis_aHitSkipsTheDatabase_aMissLoadsAndStores() {
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        ReadCache c = cache(true, true, redisWith(ops));
        when(ops.get("panchayat:cache:v1:gifts:k")).thenReturn("[\"cached\"]");
        assertEquals("[\"cached\"]", c.json("gifts", "k", Duration.ofSeconds(30), () -> { fail("must not load"); return null; }));

        when(ops.get("panchayat:cache:v1:gifts:miss")).thenReturn(null);
        assertEquals("fresh", c.json("gifts", "miss", Duration.ofSeconds(30), () -> "fresh"));
        verify(ops).set("panchayat:cache:v1:gifts:miss", "fresh", Duration.ofSeconds(30));
    }

    @Test
    @SuppressWarnings("unchecked")
    void redis_whenItFailsTheDatabaseAnswersAndRedisIsLeftAloneForAWhile() {
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(ops.get(anyString())).thenThrow(new RuntimeException("connection refused"));
        ReadCache c = cache(true, true, redisWith(ops));
        AtomicInteger loads = new AtomicInteger();
        assertEquals("v1", c.json("x", "k", Duration.ofSeconds(30), () -> "v" + loads.incrementAndGet()));
        // Redis is paused now, so the next request is served from this server's memory and Redis isn't asked again
        assertEquals("v2", c.json("x", "other", Duration.ofSeconds(30), () -> "v" + loads.incrementAndGet()));
        verify(ops, times(1)).get(anyString());
        verify(ops, never()).set(anyString(), anyString(), any(Duration.class));
        assertEquals(1.0, metrics.get("panchayat.cache.redis_errors").counter().count());
    }
}
