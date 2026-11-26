package com.panchayat.backend.games;

import com.panchayat.backend.games.gaddar.GaddarService;
import com.panchayat.backend.games.rmcs.RmcsService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Game Modes for the admin panel: how many people are playing each game right now, and a switch for each game.
 *   GET /api/v1/admin/manage/games/live
 *   PUT /api/v1/admin/manage/games/{game}/enabled   {"enabled": false}
 */
@RestController
@RequestMapping("/api/v1/admin/manage/games")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
public class AdminGamesController {

    private static final List<String> GAMES = List.of("GADDAR", "RMCS", "LUDO", "JACKAROO", "HILL", "DUO");

    private final GaddarService gaddar;
    private final RmcsService rmcs;
    private final GameSwitches switches;
    private final JdbcTemplate jdbc;
    private final com.panchayat.backend.games.hill.HillService hill;

    public AdminGamesController(GaddarService gaddar, RmcsService rmcs, GameSwitches switches, JdbcTemplate jdbc, com.panchayat.backend.games.hill.HillService hill) {
        this.hill = hill;
        this.gaddar = gaddar;
        this.rmcs = rmcs;
        this.switches = switches;
        this.jdbc = jdbc;
    }

    public record Toggle(Boolean enabled) {
    }

    @GetMapping("/live")
    public Map<String, Object> live() {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> g = gaddar.stats(false);
        rows.add(row("GADDAR", "Gaon ka Gaddaar", g.get("games"), g.get("players"), g.get("gamesInPlay")));
        Map<String, Object> r = rmcs.stats();
        Map<String, Object> rr = row("RMCS", "Raja Mantri Chor Sipahi", r.get("tables"), r.get("players"), r.get("tablesInPlay"));
        rr.put("bots", r.get("bots"));
        rows.add(rr);
        Map<String, Object> hs = hill.stats();
        rows.add(row("HILL", "Pahadi Daud", null, hs.get("playersToday"), hs.get("runsToday")));
        long duoCouples = 0, duoLevels = 0;
        try {
            duoCouples = jdbc.queryForObject("select count(distinct pair_key) from duo_results where created_at >= now() - interval '1 day'", Long.class);
            duoLevels = jdbc.queryForObject("select count(*) from duo_results where created_at >= now() - interval '1 day'", Long.class);
        } catch (Exception ignored) {
            // leave zero
        }
        rows.add(row("DUO", "Duo Dash", null, duoCouples * 2, duoLevels));
        for (String[] x : new String[][]{{"LUDO", "Ludo"}, {"JACKAROO", "Jackaroo"}}) {
            long tables = 0, playing = 0;
            try {
                tables = jdbc.queryForObject("select count(*) from room_games where game = ? and status in ('WAITING','PLAYING')", Long.class, x[0]);
                playing = jdbc.queryForObject("select count(*) from room_games where game = ? and status = 'PLAYING'", Long.class, x[0]);
            } catch (Exception ignored) {
                // leave zero
            }
            rows.add(row(x[0], x[1], tables, null, playing));
        }
        for (Map<String, Object> m : rows) m.put("enabled", switches.enabled((String) m.get("id")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("games", rows);
        out.put("updatedAt", System.currentTimeMillis());
        return out;
    }

    @PutMapping("/{game}/enabled")
    public Map<String, Object> enable(@PathVariable String game, @RequestBody Toggle body) {
        String id = game.toUpperCase(Locale.ROOT);
        if (!GAMES.contains(id) || body == null || body.enabled() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown game");
        switches.set(id, body.enabled());
        return Map.of("game", id, "enabled", body.enabled());
    }

    private static Map<String, Object> row(String id, String name, Object tables, Object players, Object inPlay) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("tables", tables);
        m.put("players", players);
        m.put("inPlay", inPlay);
        return m;
    }
}
