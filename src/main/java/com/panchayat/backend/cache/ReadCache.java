package com.panchayat.backend.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * A short-lived cache for answers that are the same for everybody (gift list, shop
 * products, ring catalogue, live counts, leaderboards). Many phones ask for these
 * every few seconds; the database answers once per time-to-live instead of once per phone.
 *
 * What is stored is the JSON text of the answer, so the app gets exactly the same bytes.
 * Never use it for anything that is per person or has to be exact: wallet, coins,
 * payments, seats, messages.
 *
 * Where it lives:
 *  - in this server's memory (default; one backend runs here, so this is the fastest), or
 *  - in Redis, if app.cache.redis.enabled=true. Redis is only worth it with more than one
 *    backend instance. If Redis is slow or down, the database is asked directly and Redis
 *    is left alone for a while, so a Redis problem never breaks a request.
 *
 * An answer that throws (a 400 for a bad parameter, for example) is never cached.
 */
@Component
public class ReadCache {

    private static final Logger log = LoggerFactory.getLogger(ReadCache.class);
    private static final String PREFIX = "panchayat:cache:v1:";
    private static final long REDIS_PAUSE_MS = 30_000;

    private record Entry(String json, long ttlNanos) {
    }

    private final Cache<String, Entry> local = Caffeine.newBuilder()
            .maximumSize(2_000)
            .expireAfter(new Expiry<String, Entry>() {
                @Override
                public long expireAfterCreate(String key, Entry value, long currentTime) {
                    return value.ttlNanos();
                }

                @Override
                public long expireAfterUpdate(String key, Entry value, long currentTime, long currentDuration) {
                    return value.ttlNanos();
                }

                @Override
                public long expireAfterRead(String key, Entry value, long currentTime, long currentDuration) {
                    return currentDuration;
                }
            })
            .build();

    private final ObjectMapper mapper;
    private final MeterRegistry metrics;
    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final boolean enabled;
    private final boolean redisEnabled;
    private volatile long redisPausedUntil;

    public ReadCache(
            ObjectMapper mapper,
            MeterRegistry metrics,
            ObjectProvider<StringRedisTemplate> redisProvider,
            @Value("${app.cache.enabled:true}") boolean enabled,
            @Value("${app.cache.redis.enabled:false}") boolean redisEnabled
    ) {
        this.mapper = mapper;
        this.metrics = metrics;
        this.redisProvider = redisProvider;
        this.enabled = enabled;
        this.redisEnabled = redisEnabled;
        log.info("Read cache: {}", !enabled ? "off" : redisEnabled ? "Redis (with the database as fallback)" : "in memory");
    }

    /** The JSON text of an answer, from the cache or else from loader (stored for ttl). */
    public String json(String name, String key, Duration ttl, Supplier<String> loader) {
        if (!enabled) return loader.get();
        String full = name + ":" + key;

        StringRedisTemplate redis = redisUsable() ? redisProvider.getIfAvailable() : null;
        if (redis != null) {
            String hit = redisGet(redis, full);
            if (hit != null) {
                count(name, "hit");
                return hit;
            }
            String value = loader.get();
            count(name, "miss");
            redisSet(redis, full, value, ttl);
            return value;
        }

        boolean[] loaded = {false};
        Entry entry = local.get(full, k -> {
            loaded[0] = true;
            return new Entry(loader.get(), ttl.toNanos());
        });
        count(name, loaded[0] ? "miss" : "hit");
        return entry.json();
    }

    /** An HTTP answer with a cached JSON body: the same bytes Spring would have written. */
    public <T> ResponseEntity<String> respond(String name, String key, Duration ttl, Supplier<T> loader) {
        String body = json(name, key, ttl, () -> mapper.writeValueAsString(loader.get()));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }

    /** A cached value, for code that needs to work with it (numbers come back as Integer or Long, whichever fits). */
    public <T> T load(String name, String key, Duration ttl, TypeReference<T> type, Supplier<T> loader) {
        String body = json(name, key, ttl, () -> mapper.writeValueAsString(loader.get()));
        return mapper.readValue(body, type);
    }

    /** Forget everything stored under a name (after an admin change that must show at once). */
    public void evict(String name) {
        String prefix = name + ":";
        local.asMap().keySet().removeIf(k -> k.startsWith(prefix));
        StringRedisTemplate redis = redisUsable() ? redisProvider.getIfAvailable() : null;
        if (redis == null) return;
        try {
            var keys = redis.keys(PREFIX + prefix + "*");
            if (keys != null && !keys.isEmpty()) redis.delete(keys);
        } catch (RuntimeException e) {
            redisFailed(e);
        }
    }

    private boolean redisUsable() {
        return redisEnabled && System.currentTimeMillis() >= redisPausedUntil;
    }

    private String redisGet(StringRedisTemplate redis, String key) {
        try {
            return redis.opsForValue().get(PREFIX + key);
        } catch (RuntimeException e) {
            redisFailed(e);
            return null;
        }
    }

    private void redisSet(StringRedisTemplate redis, String key, String value, Duration ttl) {
        if (!redisUsable()) return;
        try {
            redis.opsForValue().set(PREFIX + key, value, ttl);
        } catch (RuntimeException e) {
            redisFailed(e);
        }
    }

    private void redisFailed(RuntimeException e) {
        redisPausedUntil = System.currentTimeMillis() + REDIS_PAUSE_MS;
        metrics.counter("panchayat.cache.redis_errors").increment();
        log.warn("Redis cache not answering; using the database for {} s ({})", REDIS_PAUSE_MS / 1000, e.getClass().getSimpleName());
    }

    private void count(String name, String result) {
        metrics.counter("panchayat.cache", "name", name, "result", result).increment();
    }
}
