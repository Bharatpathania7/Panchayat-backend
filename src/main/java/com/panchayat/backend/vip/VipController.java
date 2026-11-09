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
        return m;
    }

    @PutMapping("/api/v1/admin/vip-manage/settings")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Map<String, Object> saveSettings(@RequestBody TrialRequest b) {
        vip.setFreeTrialDays(b.days() == null ? -1 : b.days());
        return settings();
    }
}
