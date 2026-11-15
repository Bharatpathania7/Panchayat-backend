package com.panchayat.backend.cp;

import com.panchayat.backend.common.PublicIds;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CP in the admin panel.
 *
 *   GET  /api/v1/admin/manage/cp/settings        ending at once: on/off and the price in coins
 *   PUT  /api/v1/admin/manage/cp/settings        { enabled, price }
 *   GET  /api/v1/admin/manage/cp/couples         who is whose CP (status = live | ended | all, q = name or ID)
 *   POST /api/v1/admin/manage/cp/couples/{id}/end   staff end a live CP
 */
@RestController
@RequestMapping("/api/v1/admin/manage/cp")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','MODERATOR')")
public class AdminCpController {

    private final JdbcTemplate jdbc;
    private final CpService cp;

    public AdminCpController(JdbcTemplate jdbc, CpService cp) {
        this.jdbc = jdbc;
        this.cp = cp;
    }

    public record SettingsRequest(Boolean enabled, Long price) {
    }

    private void audit(Authentication auth, String what, String detail) {
        jdbc.update("insert into cp_admin_audit (admin_name, what, detail) values (?, ?, ?)", auth == null ? null : auth.getName(), what, detail);
    }

    private Map<String, Object> settings() {
        Map<String, Object> s = jdbc.queryForMap("select force_end_enabled, force_end_price from cp_settings where id = 1");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", Boolean.TRUE.equals(s.get("force_end_enabled")));
        out.put("price", ((Number) s.get("force_end_price")).longValue());
        return out;
    }

    @GetMapping("/settings")
    public Map<String, Object> getSettings() {
        return settings();
    }

    @PutMapping("/settings")
    public Map<String, Object> putSettings(@RequestBody SettingsRequest b, Authentication auth) {
        if (b == null || b.enabled() == null || b.price() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Send enabled and price.");
        if (b.price() < 0 || b.price() > 1_000_000_000L) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The price must be 0 to 1,000,000,000 coins.");
        if (b.enabled() && b.price() == 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Set a price above 0 before switching this on.");
        jdbc.update("update cp_settings set force_end_enabled = ?, force_end_price = ? where id = 1", b.enabled(), b.price());
        audit(auth, "FORCE_END_SETTINGS", "enabled=" + b.enabled() + " price=" + b.price());
        return settings();
    }

    @GetMapping("/couples")
    public Map<String, Object> couples(@RequestParam(defaultValue = "live") String status,
                                       @RequestParam(required = false) String q,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "30") int size) {
        int sz = Math.max(1, Math.min(size, 100));
        int pg = Math.max(0, page);
        StringBuilder where = new StringBuilder(" where true");
        List<Object> args = new ArrayList<>();
        switch (status.toLowerCase()) {
            case "live" -> where.append(" and c.ended_at is null");
            case "ended" -> where.append(" and c.ended_at is not null");
            default -> {
            }
        }
        String text = q == null ? "" : q.trim();
        if (!text.isEmpty()) {
            Long pub = PublicIds.parse(text);
            where.append(" and (ua.full_name ilike ? or ub.full_name ilike ?");
            args.add("%" + text + "%");
            args.add("%" + text + "%");
            if (pub != null) {
                where.append(" or (1000000 + ((ua.id * 7654321 + 3141592) % 9000000 + 9000000) % 9000000) = ?");
                where.append(" or (1000000 + ((ub.id * 7654321 + 3141592) % 9000000 + 9000000) % 9000000) = ?");
                args.add(pub);
                args.add(pub);
            }
            where.append(")");
        }
        String from = " from cp_couples c join users ua on ua.id = c.user_a join users ub on ub.id = c.user_b left join cp_rings r on r.id = c.ring_id";
        Long total = jdbc.queryForObject("select count(*)" + from + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(sz);
        pageArgs.add((long) pg * sz);
        List<Map<String, Object>> rows = jdbc.query(
                "select c.id, c.user_a, c.user_b, ua.full_name as a_name, ub.full_name as b_name, c.ring_id, r.name as ring_name, c.love, c.since, c.ended_at, c.ended_how, c.ended_by, c.force_paid,"
                        + " c.end_request_by, (select count(*) from cp_couple_rings x where x.couple_id = c.id) as rings"
                        + from + where + " order by c.id desc limit ? offset ?",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    long a = rs.getLong("user_a"), b = rs.getLong("user_b");
                    m.put("id", String.valueOf(rs.getLong("id")));
                    m.put("a", person(a, rs.getString("a_name")));
                    m.put("b", person(b, rs.getString("b_name")));
                    m.put("ring", rs.getString("ring_name"));
                    m.put("love", rs.getLong("love"));
                    m.put("level", CpService.level(rs.getLong("love")));
                    m.put("rings", rs.getLong("rings"));
                    m.put("since", rs.getTimestamp("since") == null ? null : rs.getTimestamp("since").toInstant().toString());
                    m.put("endedAt", rs.getTimestamp("ended_at") == null ? null : rs.getTimestamp("ended_at").toInstant().toString());
                    m.put("endedHow", rs.getString("ended_how"));
                    Object endedBy = rs.getObject("ended_by");
                    m.put("endedBy", endedBy == null ? null : endedBy.toString());
                    m.put("forcePaid", rs.getObject("force_paid"));
                    Object req = rs.getObject("end_request_by");
                    m.put("endRequestBy", req == null ? null : req.toString());
                    return m;
                }, pageArgs.toArray());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total == null ? 0 : total);
        out.put("page", pg);
        out.put("size", sz);
        out.put("items", rows);
        return out;
    }

    private static Map<String, Object> person(long id, String name) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", String.valueOf(id));
        p.put("publicId", PublicIds.user(id));
        p.put("name", name);
        return p;
    }

    @PostMapping("/couples/{id:[0-9]+}/end")
    public Map<String, Object> end(@PathVariable long id, Authentication auth) {
        Map<String, Object> r = cp.adminEnd(id);
        audit(auth, "END_CP", "couple=" + id);
        return r;
    }
}
