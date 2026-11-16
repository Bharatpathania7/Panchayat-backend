package com.panchayat.backend.games;

import com.panchayat.backend.games.gaddar.GaddarService;
import com.panchayat.backend.games.rmcs.RmcsService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How many people are playing each game right now, for the Game Modes screen (real numbers, no estimates).
 *   GET /api/v1/games/live   → { "GADDAR": 12, "RMCS": 4, "LUDO": 8, "JACKAROO": 0 }
 */
@RestController
public class GamesLiveController {

    private final GaddarService gaddar;
    private final RmcsService rmcs;
    private final JdbcTemplate jdbc;

    public GamesLiveController(GaddarService gaddar, RmcsService rmcs, JdbcTemplate jdbc) {
        this.gaddar = gaddar;
        this.rmcs = rmcs;
        this.jdbc = jdbc;
    }

    @GetMapping("/api/v1/games/live")
    public Map<String, Object> live() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("GADDAR", gaddar.stats(false).get("players"));
        out.put("RMCS", rmcs.stats().get("players"));
        for (String g : new String[]{"LUDO", "JACKAROO"}) {
            long n = 0;
            try {
                Long v = jdbc.queryForObject("select coalesce(sum((select count(*) from jsonb_array_elements(state->'players') p where coalesce((p->>'bot')::boolean, false) = false)), 0) from room_games where game = ? and status in ('WAITING','PLAYING')", Long.class, g);
                n = v == null ? 0 : v;
            } catch (Exception ignored) {
                // leave zero
            }
            out.put(g, n);
        }
        return out;
    }
}
