package com.panchayat.backend.social.guard;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Guard in the admin panel.
 *   GET /api/v1/admin/manage/guard   { forceDeleteCost, requests: [waiting or recently refused] }
 *   PUT /api/v1/admin/manage/guard   { forceDeleteCost }   what removing a Guard right away costs
 * (An accepted removal leaves no record, so only waiting and refused requests are listed.)
 */
@RestController
@RequestMapping("/api/v1/admin/manage/guard")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
public class AdminGuardController {

    private static final long MAX_COST = 1_000_000_000L;

    private final JdbcTemplate jdbc;

    public AdminGuardController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Body(Long forceDeleteCost) {
    }

    private Map<String, Object> all() {
        Map<String, Object> out = new LinkedHashMap<>();
        Long c = jdbc.query("select force_delete_cost from guard_settings where id = 1", rs -> rs.next() ? rs.getLong(1) : null);
        out.put("forceDeleteCost", c == null ? 1_000_000L : c);
        List<Map<String, Object>> rows = jdbc.query(
                "select r.id, r.status, r.created_at, r.responded_at, f.id as fid, f.public_id as fpub, f.full_name as fname, t.id as tid, t.public_id as tpub, t.full_name as tname"
                        + " from guard_delete_requests r join users f on f.id = r.from_id join users t on t.id = r.to_id order by r.created_at desc limit 50",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", String.valueOf(rs.getLong("id")));
                    m.put("status", rs.getString("status"));
                    m.put("at", rs.getTimestamp("created_at").toInstant().toString());
                    m.put("answeredAt", rs.getTimestamp("responded_at") == null ? null : rs.getTimestamp("responded_at").toInstant().toString());
                    m.put("from", Map.of("userId", String.valueOf(rs.getLong("fid")), "publicId", String.valueOf(rs.getString("fpub")), "name", String.valueOf(rs.getString("fname"))));
                    m.put("to", Map.of("userId", String.valueOf(rs.getLong("tid")), "publicId", String.valueOf(rs.getString("tpub")), "name", String.valueOf(rs.getString("tname"))));
                    return m;
                });
        out.put("requests", rows);
        return out;
    }

    @GetMapping
    public Map<String, Object> get() {
        return all();
    }

    @PutMapping
    public Map<String, Object> put(@RequestBody Body b) {
        if (b == null || b.forceDeleteCost() == null || b.forceDeleteCost() < 0 || b.forceDeleteCost() > MAX_COST) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The price must be 0 to 1,000,000,000 coins.");
        }
        jdbc.update("update guard_settings set force_delete_cost = ? where id = 1", b.forceDeleteCost());
        return all();
    }
}
