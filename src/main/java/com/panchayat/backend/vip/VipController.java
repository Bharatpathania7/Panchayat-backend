package com.panchayat.backend.vip;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** VIP plans for the app (to buy VIP), and VIP management for staff. */
@RestController
public class VipController {

    private final VipService vip;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public VipController(VipService vip, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.vip = vip;
        this.jdbc = jdbc;
    }

    /** The plans on sale, for the VIP screen. */
    @GetMapping("/api/v1/vip/plans")
    public List<Map<String, Object>> plans() {
        return vip.plans(true);
    }

    public record PlanRequest(String name, Integer days, BigDecimal priceInRupees, Boolean active, Integer sortOrder) {
    }

    public record GiveAllRequest(Integer days, String reason) {
    }

    public record TrialRequest(Integer days) {
    }

    @GetMapping("/api/v1/admin/vip-manage/plans")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public List<Map<String, Object>> allPlans() {
        return vip.plans(false);
    }

    @PostMapping("/api/v1/admin/vip-manage/plans")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Map<String, Object> addPlan(@RequestBody PlanRequest b) {
        return vip.savePlan(null, b.name(), b.days(), b.priceInRupees(), b.active(), b.sortOrder());
    }

    @PutMapping("/api/v1/admin/vip-manage/plans/{id:[0-9]+}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Map<String, Object> editPlan(@PathVariable Long id, @RequestBody PlanRequest b) {
        return vip.savePlan(id, b.name(), b.days(), b.priceInRupees(), b.active(), b.sortOrder());
    }

    /** One click: give every user (not banned) some days of VIP. */
    @PostMapping("/api/v1/admin/vip-manage/give-all")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Map<String, Object> giveAll(@RequestBody GiveAllRequest b, Authentication auth) {
        return vip.giveAll(b.days() == null ? 0 : b.days(), b.reason(), auth == null ? null : auth.getName());
    }

    @GetMapping("/api/v1/admin/vip-manage/settings")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public Map<String, Object> settings() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("freeTrialDays", vip.freeTrialDays());
        m.put("activeVip", jdbc.queryForObject("select count(*) from users where vip_active and vip_expires_at > now()", Long.class));
        m.put("endedVip", jdbc.queryForObject("select count(*) from users where vip_expires_at is not null and vip_expires_at <= now()", Long.class));
        // The free trial for new users: how many got it, how many are on it now, and how many bought VIP after it
        m.put("trialsGiven", jdbc.queryForObject("select count(*) from vip_trials", Long.class));
        m.put("trialsToday", jdbc.queryForObject("select count(*) from vip_trials where granted_at >= date_trunc('day', now() at time zone 'Asia/Kolkata') at time zone 'Asia/Kolkata'", Long.class));
        m.put("trialsActive", jdbc.queryForObject("select count(*) from users u join vip_trials t on t.user_id = u.id where u.vip_active and u.vip_expires_at > now() and u.vip_expires_at <= t.ends_at + interval '1 minute'", Long.class));
        m.put("trialsBoughtAfter", jdbc.queryForObject("select count(*) from users u join vip_trials t on t.user_id = u.id where u.vip_expires_at > t.ends_at + interval '1 minute'", Long.class));
        return m;
    }

    /** The newest people who got the free trial: who, when, when it ends, and whether they bought VIP after it. */
    @GetMapping("/api/v1/admin/vip-manage/trials")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public java.util.List<Map<String, Object>> trials(@RequestParam(defaultValue = "50") int limit) {
        int n = Math.min(200, Math.max(1, limit));
        return jdbc.query("select t.user_id, t.days, t.granted_at, t.ends_at, u.full_name, u.email, u.public_id, u.vip_active, u.vip_expires_at"
                + " from vip_trials t join users u on u.id = t.user_id order by t.granted_at desc limit ?", (rs, i) -> {
            Map<String, Object> r = new LinkedHashMap<>();
            java.sql.Timestamp ends = rs.getTimestamp("ends_at");
            java.sql.Timestamp now = new java.sql.Timestamp(System.currentTimeMillis());
            java.sql.Timestamp vipEnd = rs.getTimestamp("vip_expires_at");
            boolean bought = vipEnd != null && vipEnd.getTime() > ends.getTime() + 60_000;
            r.put("userId", rs.getLong("user_id"));
            r.put("publicId", rs.getObject("public_id"));
            r.put("name", rs.getString("full_name"));
            r.put("email", rs.getString("email"));
            r.put("days", rs.getInt("days"));
            r.put("grantedAt", rs.getTimestamp("granted_at").toInstant().toString());
            r.put("endsAt", ends.toInstant().toString());
            r.put("status", bought ? "BOUGHT" : ends.after(now) ? "ACTIVE" : "ENDED");
            return r;
        }, n);
    }

    @PutMapping("/api/v1/admin/vip-manage/settings")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Map<String, Object> saveSettings(@RequestBody TrialRequest b) {
        vip.setFreeTrialDays(b.days() == null ? -1 : b.days());
        return settings();
    }
}
