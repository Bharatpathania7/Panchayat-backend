package com.panchayat.backend.games.duo;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin > Duo Dash: numbers, the couple ranking, the latest finished levels, and removing a couple from the ranking.
 * The on/off switch is on Game modes ("DUO").
 */
@RestController
@RequestMapping("/api/v1/admin/manage/duo")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
public class AdminDuoController {

    private static final String TODAY = "date_trunc('day', now() at time zone 'Asia/Kolkata') at time zone 'Asia/Kolkata'";
    private final JdbcTemplate jdbc;
    private final DuoService duo;

    public AdminDuoController(JdbcTemplate jdbc, DuoService duo) {
        this.jdbc = jdbc;
        this.duo = duo;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("couples", jdbc.queryForObject("select count(*) from duo_pairs", Long.class));
        m.put("levelsToday", jdbc.queryForObject("select count(*) from duo_results where created_at >= " + TODAY, Long.class));
        m.put("couplesToday", jdbc.queryForObject("select count(distinct pair_key) from duo_results where created_at >= " + TODAY, Long.class));
        m.put("levelsAllTime", jdbc.queryForObject("select count(*) from duo_results", Long.class));
        m.put("furthestLevel", jdbc.queryForObject("select coalesce(max(best_level), 0) from duo_pairs", Integer.class));
        m.put("coupleMoments", jdbc.queryForObject("select coalesce(sum(couple_moments), 0) from duo_pairs", Long.class));
        return m;
    }

    @GetMapping("/leaderboard")
    public List<Map<String, Object>> leaderboard(@RequestParam(defaultValue = "all") String period) {
        return duo.leaderboard(period, 100);
    }

    @GetMapping("/results")
    public List<Map<String, Object>> results(@RequestParam(defaultValue = "0") int page) {
        return jdbc.queryForList("select r.id, r.level_id as \"levelId\", r.level_no as \"levelNo\", r.stars, r.time_ms as \"timeMs\", r.coins,"
                + " r.couple_moments as \"coupleMoments\", r.created_at as \"createdAt\", r.pair_key as \"pair\","
                + " ua.full_name as \"nameA\", ub.full_name as \"nameB\" from duo_results r join duo_pairs p on p.pair_key = r.pair_key"
                + " join users ua on ua.id = p.user_a join users ub on ub.id = p.user_b order by r.id desc limit 100 offset ?", Math.max(0, page) * 100);
    }

    /** Takes a couple off the ranking (cheating, abuse). Their history goes with it. */
    @DeleteMapping("/pairs/{pair}")
    public Map<String, Object> remove(@PathVariable String pair) {
        if (!pair.matches("\\d+:\\d+")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bad pair");
        return Map.of("removed", jdbc.update("delete from duo_pairs where pair_key = ?", pair));
    }
}
