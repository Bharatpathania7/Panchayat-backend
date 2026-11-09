package com.panchayat.backend.vip;

import com.panchayat.backend.sarpanch.SarpanchService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * VIP plans, buying VIP, giving VIP to everyone, the free trial for new users, and telling
 * people when their VIP has ended (a message from Sarpanch and a live notice on every device
 * they have open; the app also asks them to renew the next time it opens).
 */
@Service
public class VipService {

    private static final Logger log = LoggerFactory.getLogger(VipService.class);

    public record Plan(long id, String name, int days, BigDecimal price) {
    }

    private final JdbcTemplate jdbc;
    private final SarpanchService sarpanch;
    private final SimpMessagingTemplate messaging;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "vip-expiry");
        t.setDaemon(true);
        return t;
    });

    public VipService(JdbcTemplate jdbc, @Lazy SarpanchService sarpanch, SimpMessagingTemplate messaging) {
        this.jdbc = jdbc;
        this.sarpanch = sarpanch;
        this.messaging = messaging;
        worker.scheduleWithFixedDelay(this::expiryJob, 90, 120, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        worker.shutdownNow();
    }

    /* ---------------- plans ---------------- */

    public List<Map<String, Object>> plans(boolean onlyActive) {
        return jdbc.queryForList("select id, name, days, price_in_rupees as \"priceInRupees\", active, sort_order as \"sortOrder\" from vip_plans"
                + (onlyActive ? " where active" : "") + " order by sort_order, id");
    }

    public Plan activePlan(Long id) {
        List<Plan> l = jdbc.query("select id, name, days, price_in_rupees from vip_plans where id = ? and active",
                (rs, i) -> new Plan(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getBigDecimal(4)), id);
        if (l.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That VIP plan isn't available");
        return l.get(0);
    }

    public Map<String, Object> savePlan(Long id, String name, Integer days, BigDecimal price, Boolean active, Integer sortOrder) {
        if (id == null) {
            if (name == null || name.isBlank() || days == null || price == null) throw bad("Name, days and price are required.");
            check(name, days, price);
            Long nid = jdbc.queryForObject("insert into vip_plans (name, days, price_in_rupees, active, sort_order) values (?, ?, ?, ?, ?) returning id",
                    Long.class, name.strip(), days, price, active == null || active, sortOrder == null ? 0 : sortOrder);
            return jdbc.queryForMap("select id, name, days, price_in_rupees as \"priceInRupees\", active, sort_order as \"sortOrder\" from vip_plans where id = ?", nid);
        }
        Map<String, Object> cur = jdbc.queryForList("select * from vip_plans where id = ?", id).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Plan not found"));
        String n = name != null ? name : (String) cur.get("name");
        int d = days != null ? days : ((Number) cur.get("days")).intValue();
        BigDecimal p = price != null ? price : (BigDecimal) cur.get("price_in_rupees");
        check(n, d, p);
        jdbc.update("update vip_plans set name = ?, days = ?, price_in_rupees = ?, active = ?, sort_order = ? where id = ?",
                n.strip(), d, p, active != null ? active : cur.get("active"), sortOrder != null ? sortOrder : cur.get("sort_order"), id);
        return jdbc.queryForMap("select id, name, days, price_in_rupees as \"priceInRupees\", active, sort_order as \"sortOrder\" from vip_plans where id = ?", id);
    }

    private static void check(String name, int days, BigDecimal price) {
        if (name == null || name.isBlank() || name.length() > 80) throw bad("Give the plan a name (up to 80 letters).");
        if (days < 1 || days > 3650) throw bad("Days must be 1 to 3650.");
        if (price == null || price.signum() <= 0 || price.compareTo(new BigDecimal("1000000")) > 0) throw bad("Price must be more than 0.");
    }

    /* ---------------- free trial ---------------- */

    public int freeTrialDays() {
        Integer d = jdbc.query("select free_trial_days from vip_settings where id = 1", rs -> rs.next() ? rs.getInt(1) : 3);
        return d == null ? 3 : d;
    }

    public void setFreeTrialDays(int days) {
        if (days < 0 || days > 365) throw bad("Free days must be 0 to 365.");
        jdbc.update("insert into vip_settings (id, free_trial_days) values (1, ?) on conflict (id) do update set free_trial_days = excluded.free_trial_days", days);
    }

    /* ---------------- giving VIP ---------------- */

    /** Adds days of VIP: from today if it already ended, else on top of what is left. Returns the new end date. */
    public Instant extend(Long userId, int days) {
        Timestamp end = jdbc.queryForObject(
                "update users set vip_active = true, vip_level = greatest(coalesce(vip_level, 0), 1),"
                        + " vip_expires_at = greatest(coalesce(vip_expires_at, now()), now()) + (? * interval '1 day') where id = ? returning vip_expires_at",
                Timestamp.class, days, userId);
        return end == null ? null : end.toInstant();
    }

    /** When this person's VIP ends (read fresh from the database). */
    public Instant expiry(Long userId) {
        Timestamp t = jdbc.query("select vip_expires_at from users where id = ?", rs -> rs.next() ? rs.getTimestamp(1) : null, userId);
        return t == null ? null : t.toInstant();
    }

    public int vipLevel(Long userId) {
        Integer l = jdbc.query("select coalesce(vip_level, 1) from users where id = ?", rs -> rs.next() ? rs.getInt(1) : 1, userId);
        return l == null ? 1 : l;
    }

    /** One click: every user who isn't banned gets `days` more VIP. Notices go out in the background. */
    public Map<String, Object> giveAll(int days, String reason, String admin) {
        if (days < 1 || days > 3650) throw bad("Days must be 1 to 3650.");
        String why = reason == null || reason.isBlank() ? null : reason.strip().substring(0, Math.min(200, reason.strip().length()));
        Long runId = jdbc.queryForObject("insert into admin_gift_all (kind, quantity, reason, admin_name) values ('VIP', ?, ?, ?) returning id", Long.class, days, why, admin);
        List<Long> ids = jdbc.queryForList(
                "update users set vip_active = true, vip_level = greatest(coalesce(vip_level, 0), 1),"
                        + " vip_expires_at = greatest(coalesce(vip_expires_at, now()), now()) + (? * interval '1 day')"
                        + " where role = 'USER' and not coalesce(official, false) and not (ban_permanent or coalesce(banned_until > now(), false)) returning id",
                Long.class, days);
        jdbc.update("update admin_gift_all set recipients = ?, status = 'DONE', finished_at = now() where id = ?", ids.size(), runId);
        Thread t = new Thread(() -> {
            for (Long uid : ids) {
                try {
                    sarpanch.send(uid, new SarpanchService.Message("VIP gift from Panchayat", "👑 You got " + days + " days of VIP" + (why == null ? "." : ": " + why),
                            null, "VIP benefits", "/vip", "VIP"), "vip-gift:" + runId + ":" + uid);
                    push(uid, "VIP_GRANTED");
                } catch (Exception e) {
                    log.debug("VIP gift notice not sent to {}: {}", uid, e.getMessage());
                }
            }
        }, "vip-give-all");
        t.setDaemon(true);
        t.start();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", runId);
        out.put("recipients", ids.size());
        out.put("days", days);
        return out;
    }

    /* ---------------- VIP ended ---------------- */

    /** Everyone whose VIP has ended (a free trial too) is told once per end date: Sarpanch message plus a live notice. */
    void expiryJob() {
        try {
            List<Map<String, Object>> ended = jdbc.queryForList(
                    "select id, vip_expires_at from users where vip_expires_at is not null and vip_expires_at < now()"
                            + " and vip_expiry_notice_for is distinct from vip_expires_at and not coalesce(official, false) order by vip_expires_at limit 500");
            for (Map<String, Object> u : ended) {
                long id = ((Number) u.get("id")).longValue();
                Timestamp at = (Timestamp) u.get("vip_expires_at");
                try {
                    jdbc.update("update users set vip_expiry_notice_for = ? where id = ?", at, id);
                    sarpanch.notifySafely(id, "VIP_EXPIRED", Map.of(), "vip-expired:" + id + ":" + at.toInstant().getEpochSecond());
                    push(id, "VIP_EXPIRED");
                } catch (Exception e) {
                    log.debug("VIP ended notice failed for {}: {}", id, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("VIP expiry job failed: {}", e.getMessage());
        }
    }

    /** A live notice to every device this person has open. */
    public void push(Long userId, String type) {
        try {
            String email = jdbc.query("select email from users where id = ?", rs -> rs.next() ? rs.getString(1) : null, userId);
            if (email == null) return;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", type);
            m.put("at", System.currentTimeMillis());
            messaging.convertAndSendToUser(email, "/queue/notify", (Object) m);
        } catch (Exception e) {
            log.debug("VIP notice not pushed: {}", e.getMessage());
        }
    }

    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }
}
