package com.panchayat.backend.social.visitor;

import com.panchayat.backend.admin.media.MediaUrls;
import com.panchayat.backend.wallet.service.CoinWalletService;
import com.panchayat.backend.wallet.service.WalletPushService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Profile visitors.
 *  - Opening someone's profile records a visit (one per visitor per 10 minutes).
 *  - Everyone sees how many people visited them. Who they are shows only while
 *    their visitor log is on; it's bought with coins (plans set in the admin
 *    panel), and buying again adds the days to the time left.
 *  - "I visited" (profiles you opened) is always shown.
 */
@Service
public class VisitorService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final Pattern REQUEST_ID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

    private final JdbcTemplate jdbc;
    private final CoinWalletService wallet;
    private final WalletPushService walletPush;

    public VisitorService(JdbcTemplate jdbc, CoinWalletService wallet, WalletPushService walletPush) {
        this.jdbc = jdbc;
        this.wallet = wallet;
        this.walletPush = walletPush;
    }

    @Transactional
    public Map<String, Object> visit(Long me, Long owner) {
        if (owner == null || owner.equals(me)) return Map.of("recorded", false);
        Integer exists = jdbc.queryForObject("select count(*) from users where id = ?", Integer.class, owner);
        if (exists == null || exists == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That person doesn't exist.");
        Integer recent = jdbc.queryForObject("select count(*) from profile_visits where owner_id = ? and visitor_id = ? and visited_at > now() - interval '10 minutes'",
                Integer.class, owner, me);
        if (recent != null && recent > 0) return Map.of("recorded", false);
        jdbc.update("insert into profile_visits (owner_id, visitor_id) values (?, ?)", owner, me);
        return Map.of("recorded", true);
    }

    /** kind: in (who visited me) or out (who I visited). */
    @Transactional(readOnly = true)
    public Map<String, Object> list(Long me, String kind, int limit) {
        boolean in = !"out".equalsIgnoreCase(kind);
        Map<String, Object> u = jdbc.queryForMap("select visitor_log_until, visitors_seen_at from users where id = ?", me);
        Timestamp until = (Timestamp) u.get("visitor_log_until");
        Timestamp seen = (Timestamp) u.get("visitors_seen_at");
        boolean logOn = until != null && until.toInstant().isAfter(Instant.now());
        Instant dayStart = LocalDate.now(ZONE).atStartOfDay(ZONE).toInstant();

        String me_col = in ? "owner_id" : "visitor_id";
        String other = in ? "visitor_id" : "owner_id";
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("visitors", n("select count(distinct " + other + ") from profile_visits where " + me_col + " = ?", me));
        stats.put("totalVisits", n("select count(*) from profile_visits where " + me_col + " = ?", me));
        stats.put("today", n("select count(*) from profile_visits where " + me_col + " = ? and visited_at >= ?", me, Timestamp.from(dayStart)));
        stats.put("newCount", in ? n("select count(distinct visitor_id) from profile_visits where owner_id = ? and visited_at > coalesce(?, 'epoch'::timestamptz)", me, seen) : 0);

        List<Map<String, Object>> people = new ArrayList<>();
        jdbc.query("select v." + other + " as uid, count(*) as visits, max(v.visited_at) as last_at, u.full_name, u.avatar_url"
                        + " from profile_visits v join users u on u.id = v." + other + " where v." + me_col + " = ?"
                        + " group by v." + other + ", u.full_name, u.avatar_url order by max(v.visited_at) desc limit ?",
                rs -> {
                    Map<String, Object> p = new LinkedHashMap<>();
                    Instant last = rs.getTimestamp("last_at").toInstant();
                    boolean show = !in || logOn;
                    p.put("userId", show ? String.valueOf(rs.getLong("uid")) : null);
                    p.put("name", show ? rs.getString("full_name") : "Someone");
                    p.put("avatarUrl", show ? MediaUrls.link(rs.getString("avatar_url")) : null);
                    p.put("visits", rs.getInt("visits"));
                    p.put("lastAt", last.toString());
                    p.put("isNew", in && (seen == null || last.isAfter(seen.toInstant())));
                    people.add(p);
                }, me, Math.max(1, Math.min(limit, 300)));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", in ? "in" : "out");
        out.put("stats", stats);
        out.put("logOn", logOn);
        out.put("logUntil", until == null ? null : until.toInstant().toString());
        out.put("people", people);
        return out;
    }

    @Transactional
    public Map<String, Object> markSeen(Long me) {
        jdbc.update("update users set visitors_seen_at = now() where id = ?", me);
        return Map.of("ok", true);
    }

    public List<Map<String, Object>> plans(boolean all) {
        return jdbc.queryForList("select id::text as id, label, days, price, badge, sort_order as \"sortOrder\", active from visitor_log_plans"
                + (all ? "" : " where active") + " order by sort_order, days");
    }

    /** Buy a plan with coins: the days are added to the time left. A retried request (same requestId) is charged once. */
    @Transactional
    public Map<String, Object> buy(Long me, Long planId, String requestId) {
        if (requestId == null || !REQUEST_ID.matcher(requestId).matches()) throw bad("A request id is required.");
        List<Map<String, Object>> l = jdbc.queryForList("select id, label, days, price from visitor_log_plans where id = ? and active", planId);
        if (l.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That plan isn't on sale.");
        Map<String, Object> p = l.get(0);
        long price = ((Number) p.get("price")).longValue();
        int days = ((Number) p.get("days")).intValue();
        String key = "visitor-log:" + me + ":" + requestId;
        Integer done = jdbc.queryForObject("select count(*) from coin_transactions where idempotency_key = ?", Integer.class, key);
        if (done == null || done == 0) {
            try {
                wallet.debit(me, price, "VISITOR_LOG", "visitor-plan:" + planId, key, "Visitor log " + p.get("label"));
            } catch (IllegalStateException e) {
                throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "Not enough coins.");
            }
            jdbc.update("update users set visitor_log_until = greatest(coalesce(visitor_log_until, now()), now()) + make_interval(days => ?) where id = ?", days, me);
            afterCommit(() -> walletPush.pushBalance(me, "VISITOR_LOG", -price));
        }
        Timestamp until = jdbc.queryForObject("select visitor_log_until from users where id = ?", Timestamp.class, me);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("logUntil", until == null ? null : until.toInstant().toString());
        out.put("balance", wallet.getBalance(me));
        return out;
    }

    /* ---- admin: plans ---- */

    @Transactional
    public Map<String, Object> savePlan(Long id, Map<String, Object> body) {
        String label = str(body.get("label"));
        Integer days = integer(body.get("days"));
        Long price = longOf(body.get("price"));
        String badge = str(body.get("badge"));
        Integer sort = integer(body.get("sortOrder"));
        Boolean active = body.get("active") == null ? Boolean.TRUE : Boolean.valueOf(String.valueOf(body.get("active")));
        if (label == null || label.length() > 40) throw bad("Give the plan a name (up to 40 characters).");
        if (days == null || days < 1 || days > 3650) throw bad("Days must be between 1 and 3650.");
        if (price == null || price < 1) throw bad("Price must be at least 1 coin.");
        if (badge != null && badge.length() > 20) throw bad("The badge can be up to 20 characters.");
        if (id == null) {
            Long nid = jdbc.queryForObject("insert into visitor_log_plans (label, days, price, badge, sort_order, active) values (?, ?, ?, ?, ?, ?) returning id",
                    Long.class, label, days, price, badge, sort == null ? 0 : sort, active);
            id = nid;
        } else {
            int n = jdbc.update("update visitor_log_plans set label = ?, days = ?, price = ?, badge = ?, sort_order = ?, active = ? where id = ?",
                    label, days, price, badge, sort == null ? 0 : sort, active, id);
            if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That plan doesn't exist.");
        }
        return jdbc.queryForMap("select id::text as id, label, days, price, badge, sort_order as \"sortOrder\", active from visitor_log_plans where id = ?", id);
    }

    @Transactional
    public void deletePlan(Long id) {
        jdbc.update("delete from visitor_log_plans where id = ?", id);
    }

    private long n(String sql, Object... args) {
        Long v = jdbc.queryForObject(sql, Long.class, args);
        return v == null ? 0 : v;
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).strip();
        return s.isEmpty() ? null : s;
    }

    private static Integer integer(Object o) {
        try {
            return o == null ? null : Integer.valueOf(String.valueOf(o).strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long longOf(Object o) {
        try {
            return o == null ? null : Long.valueOf(String.valueOf(o).strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }

    private static void afterCommit(Runnable r) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    r.run();
                }
            });
        } else {
            r.run();
        }
    }
}
