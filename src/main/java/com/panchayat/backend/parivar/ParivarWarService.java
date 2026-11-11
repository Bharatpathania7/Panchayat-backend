package com.panchayat.backend.parivar;

import com.panchayat.backend.admin.media.MediaUrls;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Kavach (barrier) and Chori (steal).
 *
 * Kavach protects a Parivar's treasury for a while: it is bought (from the inventory, else with funds) and switched on by someone
 * allowed to. Chori is OFF unless the admin switches it on, and then each Parivar's leader decides whether its members may use it.
 * An attempt takes a share of another Parivar's treasury if it succeeds; an active Kavach blocks it with the barrier's strength.
 * Everything (chances, shares, cooldowns, limits, who may) is set in the admin panel, and every attempt is written to a log.
 */
@Service
public class ParivarWarService {

    private static final Logger log = LoggerFactory.getLogger(ParivarWarService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private final SecureRandom random = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final ParivarService core;
    private final ParivarEconomyService economy;
    private final TransactionTemplate tx;
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "parivar-kavach");
        t.setDaemon(true);
        return t;
    });

    public ParivarWarService(JdbcTemplate jdbc, ParivarService core, ParivarEconomyService economy, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.core = core;
        this.economy = economy;
        this.tx = tx;
        ticker.scheduleWithFixedDelay(this::expiryNotices, 150, 120, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        ticker.shutdownNow();
    }

    private static long l(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }

    private Timestamp dayStart() {
        return Timestamp.from(LocalDate.now(IST).atStartOfDay(IST).toInstant());
    }

    /* ======================= Kavach ======================= */

    /** The barrier switched on right now (the strongest if more than one), or null. */
    Map<String, Object> activeBarrier(Long parivarId) {
        List<Map<String, Object>> l = jdbc.queryForList("select * from parivar_barriers where parivar_id = ? and expires_at > now() order by strength desc, expires_at desc limit 1", parivarId);
        return l.isEmpty() ? null : l.get(0);
    }

    public Map<String, Object> kavach(Long userId, Long parivarId) {
        Map<String, Object> p = core.activeParivar(parivarId);
        if (core.roleOf(parivarId, userId) == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only members can see Kavach.");
        int level = ((Number) p.get("level")).intValue();
        Map<String, Object> active = activeBarrier(parivarId);
        List<Map<String, Object>> types = jdbc.query("select t.*, coalesce((select qty from parivar_inventory i where i.parivar_id = ? and i.kind = 'BARRIER' and i.ref = t.id), 0) as owned,"
                        + " (select max(b.starts_at) from parivar_barriers b where b.parivar_id = ? and b.type_id = t.id) as last_on,"
                        + " (select count(*) from parivar_barriers b where b.parivar_id = ? and b.type_id = t.id and b.starts_at >= ?) as used_today"
                        + " from parivar_barrier_types t where t.active order by t.sort_order, t.id",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", String.valueOf(rs.getLong("id")));
                    m.put("name", rs.getString("name"));
                    m.put("imageUrl", MediaUrls.link(rs.getString("image_url")));
                    m.put("strength", rs.getInt("strength"));
                    m.put("durationMinutes", rs.getInt("duration_minutes"));
                    m.put("priceFunds", rs.getLong("price_funds"));
                    m.put("cooldownMinutes", rs.getInt("cooldown_minutes"));
                    m.put("dailyLimit", rs.getInt("daily_limit"));
                    m.put("minLevel", rs.getInt("min_level"));
                    m.put("locked", level < rs.getInt("min_level"));
                    m.put("owned", rs.getLong("owned"));
                    m.put("usedToday", rs.getLong("used_today"));
                    Timestamp last = rs.getTimestamp("last_on");
                    long cdLeft = last == null ? 0 : Math.max(0, rs.getInt("cooldown_minutes") * 60L - Duration.between(last.toInstant(), Instant.now()).getSeconds());
                    m.put("cooldownLeftSeconds", cdLeft);
                    return m;
                }, parivarId, parivarId, parivarId, dayStart());
        List<Map<String, Object>> history = jdbc.query("select b.name, b.strength, b.starts_at, b.expires_at, u.full_name from parivar_barriers b left join users u on u.id = b.activated_by where b.parivar_id = ? order by b.id desc limit 10",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", rs.getString("name"));
                    m.put("strength", rs.getInt("strength"));
                    m.put("by", rs.getString("full_name"));
                    m.put("startsAt", rs.getTimestamp("starts_at").toInstant().toString());
                    m.put("expiresAt", rs.getTimestamp("expires_at").toInstant().toString());
                    return m;
                }, parivarId);
        Map<String, Object> res = new LinkedHashMap<>();
        if (active != null) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("name", active.get("name"));
            a.put("strength", active.get("strength"));
            a.put("expiresAt", ((Timestamp) active.get("expires_at")).toInstant().toString());
            res.put("active", a);
        } else {
            res.put("active", null);
        }
        res.put("types", types);
        res.put("history", history);
        res.put("canManage", core.can(parivarId, userId, "MANAGE_BARRIER"));
        res.put("funds", l(p.get("funds")));
        return res;
    }

    @Transactional
    public Map<String, Object> activateKavach(Long userId, Long parivarId, Long typeId, String key) {
        if (key == null || key.isBlank() || key.length() > 60) throw ParivarService.bad("A request key is required.");
        Map<String, Object> p = core.activeParivar(parivarId);
        core.require(parivarId, userId, "MANAGE_BARRIER");
        List<Map<String, Object>> tl = jdbc.queryForList("select * from parivar_barrier_types where id = ? and active", typeId);
        if (tl.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That Kavach isn't available.");
        Map<String, Object> t = tl.get(0);
        String ref = "kavach:" + parivarId + ":" + userId + ":" + key.strip();
        if (l(jdbc.queryForObject("select count(*) from parivar_funds_ledger where ref = ?", Long.class, ref)) > 0) return kavach(userId, parivarId);
        if (jdbc.queryForList("select 1 from parivar_barriers where ref_key = ?", ref).size() > 0) return kavach(userId, parivarId);
        if (((Number) p.get("level")).intValue() < ((Number) t.get("min_level")).intValue()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Your Parivar needs level " + t.get("min_level") + " for this Kavach.");
        int strength = ((Number) t.get("strength")).intValue();
        Map<String, Object> cur = activeBarrier(parivarId);
        if (cur != null && ((Number) cur.get("strength")).intValue() >= strength)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A Kavach as strong or stronger is already on until " + ((Timestamp) cur.get("expires_at")).toInstant() + ".");
        int cooldown = ((Number) t.get("cooldown_minutes")).intValue();
        Timestamp last = jdbc.query("select max(starts_at) from parivar_barriers where parivar_id = ? and type_id = ?", rs -> rs.next() ? rs.getTimestamp(1) : null, parivarId, typeId);
        if (last != null && Duration.between(last.toInstant(), Instant.now()).toMinutes() < cooldown) throw new ResponseStatusException(HttpStatus.CONFLICT, "This Kavach is cooling down. Try again later.");
        int limit = ((Number) t.get("daily_limit")).intValue();
        if (limit > 0 && l(jdbc.queryForObject("select count(*) from parivar_barriers where parivar_id = ? and type_id = ? and starts_at >= ?", Long.class, parivarId, typeId, dayStart())) >= limit)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You've used this Kavach the most times allowed today.");
        // From the inventory if there is one, else paid from the treasury
        if (!economy.takeInventory(parivarId, "BARRIER", typeId, 1)) {
            long price = l(t.get("price_funds"));
            if (price > 0) core.changeFunds(parivarId, userId, -price, "KAVACH", ref);
        }
        Timestamp expires = Timestamp.from(Instant.now().plus(Duration.ofMinutes(((Number) t.get("duration_minutes")).longValue())));
        jdbc.update("insert into parivar_barriers (parivar_id, type_id, name, strength, activated_by, expires_at, ref_key) values (?, ?, ?, ?, ?, ?, ?)", parivarId, typeId, t.get("name"), strength, userId, expires, ref);
        core.activity(parivarId, userId, null, "KAVACH", 0, 0, "switched on " + t.get("name"), null);
        core.system(parivarId, "🛡️ " + t.get("name") + " is on. The treasury is protected.");
        return kavach(userId, parivarId);
    }

    /** Tells each Parivar once when its Kavach has run out. */
    void expiryNotices() {
        try {
            for (Map<String, Object> b : jdbc.queryForList("select id, parivar_id, name from parivar_barriers where not notified and expires_at <= now() limit 200")) {
                long id = l(b.get("id"));
                try {
                    tx.executeWithoutResult(s -> {
                        if (jdbc.update("update parivar_barriers set notified = true where id = ? and not notified", id) == 0) return;
                        long pid = l(b.get("parivar_id"));
                        if (activeBarrier(pid) == null) {
                            core.activity(pid, 0L, null, "KAVACH", 0, 0, b.get("name") + " ran out", null);
                            core.system(pid, "⚠️ " + b.get("name") + " has run out. The treasury is not protected now.");
                        }
                    });
                } catch (Exception e) {
                    log.debug("Kavach notice {} failed: {}", id, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.debug("Kavach notices failed: {}", e.getMessage());
        }
    }

    /* ======================= Chori ======================= */

    private Map<String, Object> rules() {
        return core.settings();
    }

    /** Why this person can't use Chori right now, or null if they can. */
    private String blocked(Map<String, Object> s, Map<String, Object> p, Long parivarId, Long userId) {
        if (!Boolean.TRUE.equals(s.get("steal_enabled"))) return "Chori is switched off in Panchayat right now.";
        if (((Number) p.get("level")).intValue() < ((Number) s.get("steal_min_level")).intValue()) return "Your Parivar needs level " + s.get("steal_min_level") + " to use Chori.";
        String role = core.roleOf(parivarId, userId);
        if (role == null) return "Only members can use Chori.";
        if (!"LEADER".equals(role)) {
            if (!Boolean.TRUE.equals(p.get("steal_on"))) return "Chori is switched off by your Mukhiya.";
            if (!core.can(parivarId, userId, "USE_STEAL")) return "You don't have permission to use Chori.";
        }
        return null;
    }

    public Map<String, Object> chori(Long userId, Long parivarId) {
        Map<String, Object> s = rules();
        Map<String, Object> p = core.activeParivar(parivarId);
        if (core.roleOf(parivarId, userId) == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only members can see Chori.");
        Map<String, Object> m = new LinkedHashMap<>();
        boolean globalOn = Boolean.TRUE.equals(s.get("steal_enabled"));
        m.put("enabled", globalOn);
        m.put("membersAllowed", Boolean.TRUE.equals(p.get("steal_on")));
        m.put("canToggle", core.can(parivarId, userId, "MANAGE_STEAL"));
        String why = blocked(s, p, parivarId, userId);
        m.put("blockedReason", why);
        long usedToday = l(jdbc.queryForObject("select count(*) from parivar_steal_log where thief_parivar = ? and created_at >= ?", Long.class, parivarId, dayStart()));
        int daily = ((Number) s.get("steal_daily_attempts")).intValue();
        long extraAttempts = l(jdbc.query("select qty from parivar_inventory where parivar_id = ? and kind = 'ATTEMPT' and ref = 0", rs -> rs.next() ? rs.getLong(1) : 0L, parivarId));
        m.put("attemptsLeft", Math.max(0, daily - usedToday) + extraAttempts);
        m.put("extraAttempts", extraAttempts);
        m.put("dailyAttempts", daily);
        Timestamp last = jdbc.query("select max(created_at) from parivar_steal_log where thief_parivar = ?", rs -> rs.next() ? rs.getTimestamp(1) : null, parivarId);
        long cd = ((Number) s.get("steal_cooldown_minutes")).longValue() * 60;
        m.put("cooldownLeftSeconds", last == null ? 0 : Math.max(0, cd - Duration.between(last.toInstant(), Instant.now()).getSeconds()));
        m.put("successPercent", s.get("steal_success_percent"));
        m.put("takePercent", s.get("steal_take_percent"));
        m.put("maxTake", l(s.get("steal_max_take")));
        m.put("failPenalty", l(s.get("steal_fail_penalty")));
        Map<String, Object> mine = activeBarrier(parivarId);
        m.put("myKavach", mine == null ? null : Map.of("name", mine.get("name"), "strength", mine.get("strength"), "expiresAt", ((Timestamp) mine.get("expires_at")).toInstant().toString()));
        // Targets: other Parivars with enough in the treasury (we show whether a Kavach is on, not how strong)
        long minFunds = l(s.get("steal_min_target_funds"));
        List<Map<String, Object>> targets = globalOn ? jdbc.query("select p.id, p.name, p.sign, p.emblem, p.color, p.level, p.funds, exists (select 1 from parivar_barriers b where b.parivar_id = p.id and b.expires_at > now()) as kavach"
                        + " from parivars p where p.status = 'ACTIVE' and p.id <> ? and p.funds >= ? order by p.funds desc limit 20",
                (rs, i) -> {
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("id", String.valueOf(rs.getLong("id")));
                    t.put("name", rs.getString("name"));
                    t.put("sign", rs.getString("sign"));
                    t.put("emblem", rs.getString("emblem"));
                    t.put("color", rs.getString("color"));
                    t.put("level", rs.getInt("level"));
                    t.put("funds", rs.getLong("funds"));
                    t.put("kavach", rs.getBoolean("kavach"));
                    return t;
                }, parivarId, minFunds) : List.of();
        m.put("targets", targets);
        m.put("history", history(parivarId));
        return m;
    }

    private List<Map<String, Object>> history(Long parivarId) {
        return jdbc.query("select l.id, l.thief_parivar, l.target_parivar, l.result, l.amount, l.created_at, u.full_name, tp.name as thief_name, vp.name as target_name from parivar_steal_log l"
                        + " left join users u on u.id = l.thief_user join parivars tp on tp.id = l.thief_parivar join parivars vp on vp.id = l.target_parivar"
                        + " where l.thief_parivar = ? or l.target_parivar = ? order by l.id desc limit 20",
                (rs, i) -> {
                    boolean mine = rs.getLong("thief_parivar") == parivarId;
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", String.valueOf(rs.getLong("id")));
                    m.put("direction", mine ? "OUT" : "IN");
                    m.put("result", rs.getString("result"));
                    m.put("amount", rs.getLong("amount"));
                    m.put("who", mine ? rs.getString("full_name") : null);
                    m.put("other", mine ? rs.getString("target_name") : rs.getString("thief_name"));
                    m.put("at", rs.getTimestamp("created_at").toInstant().toString());
                    return m;
                }, parivarId, parivarId);
    }

    /** The leader's switch: may the other members use Chori. */
    @Transactional
    public Map<String, Object> setMembersAllowed(Long userId, Long parivarId, boolean on) {
        core.activeParivar(parivarId);
        core.require(parivarId, userId, "MANAGE_STEAL");
        jdbc.update("update parivars set steal_on = ? where id = ?", on, parivarId);
        core.activity(parivarId, userId, null, "STEAL", 0, 0, on ? "allowed members to use Chori" : "switched Chori off for members", null);
        return chori(userId, parivarId);
    }

    @Transactional
    public Map<String, Object> attempt(Long userId, Long parivarId, Long targetId, String key) {
        if (key == null || key.isBlank() || key.length() > 60) throw ParivarService.bad("A request key is required.");
        Map<String, Object> s = rules();
        Map<String, Object> p = core.activeParivar(parivarId);
        String why = blocked(s, p, parivarId, userId);
        if (why != null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, why);
        if (parivarId.equals(targetId)) throw ParivarService.bad("You can't take from your own Parivar.");
        String ref = "chori:" + parivarId + ":" + userId + ":" + key.strip();
        // A retry of an attempt that already ran: the same answer, nothing happens twice
        List<Map<String, Object>> done = jdbc.queryForList("select result, amount from parivar_steal_log where ref = ?", ref);
        if (!done.isEmpty()) return Map.of("result", done.get(0).get("result"), "amount", l(done.get(0).get("amount")));

        // Lock both treasuries in a fixed order so two attempts at once can't deadlock or overdraw
        long a = Math.min(parivarId, targetId), b = Math.max(parivarId, targetId);
        jdbc.queryForList("select id from parivars where id in (?, ?) order by id for update", a, b);
        Map<String, Object> target = core.activeParivar(targetId);
        Map<String, Object> me = core.activeParivar(parivarId);

        int daily = ((Number) s.get("steal_daily_attempts")).intValue();
        // Today's attempts first; when they are used up, a Stealing Attempt won in Gacha is used instead
        if (l(jdbc.queryForObject("select count(*) from parivar_steal_log where thief_parivar = ? and created_at >= ?", Long.class, parivarId, dayStart())) >= daily) {
            if (jdbc.update("update parivar_inventory set qty = qty - 1 where parivar_id = ? and kind = 'ATTEMPT' and ref = 0 and qty >= 1", parivarId) == 0)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Your Parivar has used all of today's Chori attempts.");
        }
        Timestamp last = jdbc.query("select max(created_at) from parivar_steal_log where thief_parivar = ?", rs -> rs.next() ? rs.getTimestamp(1) : null, parivarId);
        long cdMin = ((Number) s.get("steal_cooldown_minutes")).longValue();
        if (last != null && Duration.between(last.toInstant(), Instant.now()).toMinutes() < cdMin) throw new ResponseStatusException(HttpStatus.CONFLICT, "Chori is cooling down. Try again in a while.");
        long minFunds = l(s.get("steal_min_target_funds"));
        if (l(target.get("funds")) < minFunds) throw new ResponseStatusException(HttpStatus.CONFLICT, "That Parivar doesn't have enough in its treasury.");

        Map<String, Object> barrier = activeBarrier(targetId);
        int strength = barrier == null ? 0 : ((Number) barrier.get("strength")).intValue();
        String result;
        long amount = 0;
        if (strength > 0 && random.nextInt(100) < strength) {
            result = "BLOCKED"; // the target's Kavach held
        } else if (random.nextInt(100) < ((Number) s.get("steal_success_percent")).intValue()) {
            result = "SUCCESS";
            long share = (long) Math.floor(l(target.get("funds")) * ((java.math.BigDecimal) s.get("steal_take_percent")).doubleValue() / 100.0);
            amount = Math.max(0, Math.min(share, Math.min(l(s.get("steal_max_take")), l(target.get("funds")))));
            if (amount > 0) {
                core.changeFunds(targetId, userId, -amount, "CHORI", ref + ":out");
                core.changeFunds(parivarId, userId, amount, "CHORI", ref + ":in");
            }
        } else {
            result = "FAILED";
            long penalty = Math.min(l(s.get("steal_fail_penalty")), l(me.get("funds")));
            if (penalty > 0) core.changeFunds(parivarId, userId, -penalty, "CHORI", ref + ":fine");
            amount = -penalty;
        }
        jdbc.update("insert into parivar_steal_log (thief_parivar, thief_user, target_parivar, result, amount, barrier_strength, ref) values (?, ?, ?, ?, ?, ?, ?)", parivarId, userId, targetId, result, Math.max(0, amount), strength, ref);
        String tn = (String) target.get("name"), mn = (String) me.get("name");
        switch (result) {
            case "SUCCESS" -> {
                core.activity(parivarId, userId, null, "STEAL", 0, amount, "took " + amount + " funds from " + tn + " (Chori)", null);
                core.activity(targetId, userId, null, "STEAL", 0, amount, mn + " took " + amount + " funds from the treasury (Chori)", null);
                core.world("ATTACK", parivarId, targetId, "\uD83E\uDD77 " + mn + " attacked " + tn + " and took " + amount + " funds from its treasury!", amount);
                core.system(targetId, "🥷 " + mn + " took " + amount + " funds from the treasury!");
                core.system(parivarId, "🥷 Chori worked! " + amount + " funds from " + tn + ".");
            }
            case "BLOCKED" -> {
                core.activity(targetId, userId, null, "STEAL", 0, 0, "Kavach stopped a Chori by " + mn, null);
                core.system(targetId, "🛡️ Your Kavach stopped a Chori by " + mn + "!");
                core.system(parivarId, "🛡️ " + tn + "'s Kavach held. Chori failed.");
            }
            default -> core.system(parivarId, "😅 Chori on " + tn + " failed" + (amount < 0 ? " and cost " + (-amount) + " funds." : "."));
        }
        return Map.of("result", result, "amount", Math.abs(amount));
    }
}
