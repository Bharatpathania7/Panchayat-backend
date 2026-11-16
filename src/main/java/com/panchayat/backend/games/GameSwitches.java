package com.panchayat.backend.games;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Which game modes are switched on (the admin can turn one off). Read from the database at most every 10 seconds. */
@Component
public class GameSwitches {
    private final JdbcTemplate jdbc;
    private final Map<String, Boolean> cache = new ConcurrentHashMap<>();
    private volatile long loadedAt = 0;

    public GameSwitches(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private void load() {
        if (System.currentTimeMillis() - loadedAt < 10_000) return;
        loadedAt = System.currentTimeMillis();
        try {
            cache.clear();
            jdbc.query("select game, enabled from game_settings", rs -> {
                cache.put(rs.getString(1), rs.getBoolean(2));
            });
        } catch (Exception ignored) {
            // the table is optional: everything stays on
        }
    }

    public boolean enabled(String game) {
        load();
        return cache.getOrDefault(game, true);
    }

    public void require(String game) {
        if (!enabled(game)) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "This game is switched off for now. Try again later.");
    }

    public void set(String game, boolean on) {
        jdbc.update("insert into game_settings (game, enabled, updated_at) values (?, ?, now()) on conflict (game) do update set enabled = excluded.enabled, updated_at = now()", game, on);
        cache.put(game, on);
    }
}
