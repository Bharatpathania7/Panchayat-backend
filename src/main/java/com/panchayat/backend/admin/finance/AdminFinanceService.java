package com.panchayat.backend.admin.finance;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * Money and audit reports for Super Admins: revenue (successful top-ups),
 * payments, every coin movement, and the admin audit log. Days are counted in
 * India time.
 */
@Service
public class AdminFinanceService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Set<String> PAYMENT_STATUSES = Set.of("PENDING", "SUCCESS", "FAILED");
    private static final Set<String> PURPOSES = Set.of("COIN_PACK", "RED_PACKET", "VIP");

    private final JdbcTemplate jdbc;

    public AdminFinanceService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /* ---------------- revenue ---------------- */

    public Map<String, Object> summary(int days) {
        int d = Math.max(7, Math.min(365, days));
        LocalDate today = LocalDate.now(IST);
        Instant startToday = today.atStartOfDay(IST).toInstant();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("currency", "INR");
        out.put("allTime", revenueSince(Instant.EPOCH));
        out.put("today", revenueSince(startToday));
        out.put("last7Days", revenueSince(today.minusDays(6).atStartOfDay(IST).toInstant()));
        out.put("last30Days", revenueSince(today.minusDays(29).atStartOfDay(IST).toInstant()));
        out.put("thisMonth", revenueSince(today.withDayOfMonth(1).atStartOfDay(IST).toInstant()));

        Map<String, Long> statuses = new LinkedHashMap<>();
        for (String s : List.of("SUCCESS", "PENDING", "FAILED")) statuses.put(s, 0L);
        jdbc.query("select status, count(*) from payment_transactions group by status",
                rs -> {
                    statuses.put(rs.getString(1), rs.getLong(2));
                });
        out.put("paymentsByStatus", statuses);

        out.put("byPurpose", jdbc.query(
                "select purpose, coalesce(sum(amount), 0), count(*) from payment_transactions where status = 'SUCCESS' group by purpose order by 2 desc",
                (rs, i) -> Map.of("purpose", rs.getString(1), "amount", rs.getBigDecimal(2), "count", rs.getLong(3))));

        out.put("daily", jdbc.query(
                "select d::date, coalesce(sum(p.amount), 0), count(p.id)"
                        + " from generate_series(?::date, ?::date, interval '1 day') d"
                        + " left join payment_transactions p on p.status = 'SUCCESS'"
                        + "   and (p.created_at at time zone 'Asia/Kolkata')::date = d::date"
                        + " group by d order by d",
                (rs, i) -> Map.of("day", rs.getString(1), "amount", rs.getBigDecimal(2), "count", rs.getLong(3)),
                today.minusDays(d - 1L).toString(), today.toString()));

        out.put("topPayers", jdbc.query(
                "select p.user_id, u.full_name, u.email, sum(p.amount), count(*) from payment_transactions p"
                        + " left join users u on u.id = p.user_id where p.status = 'SUCCESS'"
                        + " group by p.user_id, u.full_name, u.email order by 4 desc limit 10",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("userId", rs.getLong(1));
                    m.put("name", rs.getString(2));
                    m.put("email", rs.getString(3));
                    m.put("amount", rs.getBigDecimal(4));
                    m.put("count", rs.getLong(5));
                    return m;
                }));

        Map<String, Object> coins = new LinkedHashMap<>();
        coins.put("inWallets", jdbc.queryForObject("select coalesce(sum(balance), 0) from coin_wallets", Long.class));
        coins.put("sold", jdbc.queryForObject(
                "select coalesce(sum(coalesce(purchased_coins, 0) + coalesce(purchased_bonus_coins, 0)), 0) from payment_transactions where status = 'SUCCESS'",
                Long.class));
        coins.put("byReason", jdbc.query(
                "select reason, transaction_type, coalesce(sum(amount), 0), count(*) from coin_transactions group by reason, transaction_type order by 3 desc",
                (rs, i) -> Map.of("reason", rs.getString(1), "type", rs.getString(2), "coins", rs.getLong(3), "count", rs.getLong(4))));
        out.put("coins", coins);
        return out;
    }

    private Map<String, Object> revenueSince(Instant since) {
        return jdbc.queryForObject(
                "select coalesce(sum(amount), 0), count(*), count(distinct user_id) from payment_transactions where status = 'SUCCESS' and created_at >= ?",
                (rs, i) -> {
                    BigDecimal amount = rs.getBigDecimal(1);
                    long count = rs.getLong(2);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("amount", amount);
                    m.put("count", count);
                    m.put("payers", rs.getLong(3));
                    m.put("average", count == 0 ? BigDecimal.ZERO : amount.divide(BigDecimal.valueOf(count), 2, java.math.RoundingMode.HALF_UP));
                    return m;
                }, Timestamp.from(since));
    }

    /* ---------------- payments ---------------- */

    public Map<String, Object> payments(int page, int size, String status, String purpose, String q) {
        List<String> where = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            String s = status.trim().toUpperCase(Locale.ROOT);
            if (!PAYMENT_STATUSES.contains(s)) throw bad("Unknown status");
            where.add("p.status = ?");
            args.add(s);
        }
        if (purpose != null && !purpose.isBlank()) {
            String s = purpose.trim().toUpperCase(Locale.ROOT);
            if (!PURPOSES.contains(s)) throw bad("Unknown purpose");
            where.add("p.purpose = ?");
            args.add(s);
        }
        if (q != null && !q.isBlank()) {
            String term = q.trim();
            if (term.matches("\\d{1,18}")) {
                where.add("(p.user_id = ? or p.txn_id ilike ?)");
                args.add(Long.parseLong(term));
            } else {
                where.add("(p.txn_id ilike ? or p.payu_payment_id ilike ? or u.email ilike ?)");
                args.add(like(term));
                args.add(like(term));
            }
            args.add(like(term));
        }
        return page("from payment_transactions p left join users u on u.id = p.user_id",
                "p.id, p.txn_id, p.user_id, u.full_name, u.email, p.purpose, p.product_id, p.amount, p.currency, p.status,"
                        + " p.payu_payment_id, p.payment_mode, p.failure_reason, p.purchased_coins, p.purchased_bonus_coins,"
                        + " p.created_at, p.verified_at",
                where, args, "p.created_at desc, p.id desc", page, size,
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("txnId", rs.getString("txn_id"));
                    m.put("userId", rs.getLong("user_id"));
                    m.put("userName", rs.getString("full_name"));
                    m.put("userEmail", rs.getString("email"));
                    m.put("purpose", rs.getString("purpose"));
                    m.put("productId", rs.getObject("product_id"));
                    m.put("amount", rs.getBigDecimal("amount"));
                    m.put("currency", rs.getString("currency"));
                    m.put("status", rs.getString("status"));
                    m.put("payuPaymentId", rs.getString("payu_payment_id"));
                    m.put("paymentMode", rs.getString("payment_mode"));
                    m.put("failureReason", rs.getString("failure_reason"));
                    m.put("coins", rs.getObject("purchased_coins"));
                    m.put("bonusCoins", rs.getObject("purchased_bonus_coins"));
                    m.put("createdAt", ts(rs, "created_at"));
                    m.put("verifiedAt", ts(rs, "verified_at"));
                    return m;
                });
    }

    /* ---------------- coin logs ---------------- */

    public Map<String, Object> coinTransactions(int page, int size, String reason, String type, Long userId) {
        List<String> where = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (reason != null && !reason.isBlank()) {
            where.add("t.reason = ?");
            args.add(reason.trim().toUpperCase(Locale.ROOT));
        }
        if (type != null && !type.isBlank()) {
            String t = type.trim().toUpperCase(Locale.ROOT);
            if (!t.equals("CREDIT") && !t.equals("DEBIT")) throw bad("Type must be CREDIT or DEBIT");
            where.add("t.transaction_type = ?");
            args.add(t);
        }
        if (userId != null) {
            where.add("t.user_id = ?");
            args.add(userId);
        }
        return page("from coin_transactions t left join users u on u.id = t.user_id",
                "t.id, t.user_id, u.full_name, t.transaction_type, t.amount, t.balance_before, t.balance_after, t.reason,"
                        + " t.reference_id, t.description, t.created_at",
                where, args, "t.created_at desc, t.id desc", page, size,
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("userId", rs.getLong("user_id"));
                    m.put("userName", rs.getString("full_name"));
                    m.put("type", rs.getString("transaction_type"));
                    m.put("amount", rs.getLong("amount"));
                    m.put("balanceBefore", rs.getLong("balance_before"));
                    m.put("balanceAfter", rs.getLong("balance_after"));
                    m.put("reason", rs.getString("reason"));
                    m.put("referenceId", rs.getString("reference_id"));
                    m.put("description", rs.getString("description"));
                    m.put("createdAt", ts(rs, "created_at"));
                    return m;
                });
    }

    public List<String> coinReasons() {
        return jdbc.queryForList("select distinct reason from coin_transactions order by reason", String.class);
    }

    /* ---------------- audit log ---------------- */

    public Map<String, Object> audit(int page, int size, String q, String method) {
        List<String> where = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            where.add("(admin_username ilike ? or path ilike ?)");
            args.add(like(q.trim()));
            args.add(like(q.trim()));
        }
        if (method != null && !method.isBlank()) {
            where.add("method = ?");
            args.add(method.trim().toUpperCase(Locale.ROOT));
        }
        return page("from admin_audit_logs", "id, admin_username, admin_role, method, path, status, ip, created_at",
                where, args, "created_at desc, id desc", page, size,
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("admin", rs.getString("admin_username"));
                    m.put("role", rs.getString("admin_role"));
                    m.put("method", rs.getString("method"));
                    m.put("path", rs.getString("path"));
                    m.put("status", rs.getInt("status"));
                    m.put("ip", rs.getString("ip"));
                    m.put("createdAt", ts(rs, "created_at"));
                    return m;
                });
    }

    /* ---------------- internals ---------------- */

    private Map<String, Object> page(String from, String columns, List<String> where, List<Object> args, String order,
                                     int page, int size, RowMapper<Map<String, Object>> mapper) {
        int p = Math.max(0, page);
        int s = Math.min(100, Math.max(1, size));
        String w = where.isEmpty() ? "" : " where " + String.join(" and ", where);
        Long total = jdbc.queryForObject("select count(*) " + from + w, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add((long) p * s);
        List<Map<String, Object>> rows = jdbc.query("select " + columns + " " + from + w + " order by " + order + " limit ? offset ?",
                mapper, pageArgs.toArray());
        long t = total == null ? 0 : total;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", rows);
        out.put("page", p);
        out.put("size", s);
        out.put("totalElements", t);
        out.put("totalPages", (t + s - 1) / s);
        return out;
    }

    private static Instant ts(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    private static String like(String term) {
        return "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }
}
