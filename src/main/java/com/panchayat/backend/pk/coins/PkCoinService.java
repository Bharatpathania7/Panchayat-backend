package com.panchayat.backend.pk.coins;

import com.panchayat.backend.admin.media.MediaUrls;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/**
 * PK Coins: a currency of its own (never mixed with coins). Earned by sending gifts during a live PK, spent in the
 * Shop's PK category, granted or taken back by staff with a reason. Every change is one row in pk_coin_transactions
 * with a unique key, so a retry never pays or charges twice, and the balance can never go below zero.
 */
@Service
public class PkCoinService {

    private static final Logger log = LoggerFactory.getLogger(PkCoinService.class);
    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    private final JdbcTemplate jdbc;
    private final SimpMessagingTemplate messaging;

    public PkCoinService(JdbcTemplate jdbc, SimpMessagingTemplate messaging) {
        this.jdbc = jdbc;
        this.messaging = messaging;
    }

    public record Settings(boolean enabled, int percent, int minGiftCoins, int officialBonus, int itemDays) {}

    public Settings settings() {
        return jdbc.query("select enabled, percent, min_gift_coins, official_bonus, item_days from pk_coin_settings where id = 1",
                rs -> rs.next() ? new Settings(rs.getBoolean(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5)) : new Settings(true, 10, 1, 0, 7));
    }

    @Transactional
    public Settings saveSettings(Settings s) {
        if (s.percent() < 0 || s.percent() > 1000) throw bad("Percent must be 0 to 1000.");
        if (s.minGiftCoins() < 0) throw bad("Minimum gift must be 0 or more.");
        if (s.officialBonus() < 0 || s.officialBonus() > 1000) throw bad("Official bonus must be 0 to 1000 percent.");
        if (s.itemDays() < 1 || s.itemDays() > 365) throw bad("PK Shop item days must be 1 to 365.");
        jdbc.update("update pk_coin_settings set enabled = ?, percent = ?, min_gift_coins = ?, official_bonus = ?, item_days = ?, updated_at = now() where id = 1",
                s.enabled(), s.percent(), s.minGiftCoins(), s.officialBonus(), s.itemDays());
        return settings();
    }

    /**
     * A gift sent during a live PK: the sender earns PK Coins (percent of the gift's coins; in an Official PK room the
     * official bonus adds that percent on top). Runs inside the gift's transaction; the gift's request id makes it count once.
     */
    @Transactional
    public long earnFromGift(Long roomId, Long senderId, long coins, String requestId) {
        if (senderId == null || coins <= 0 || requestId == null) return 0;
        Settings s = settings();
        if (!s.enabled() || coins < s.minGiftCoins()) return 0;
        Boolean official = jdbc.query("select official_pk from voice_rooms where id = ?", rs -> rs.next() && rs.getBoolean(1), roomId);
        long amount = coins * s.percent() / 100;
        if (Boolean.TRUE.equals(official)) amount += amount * s.officialBonus() / 100;
        if (amount <= 0) return 0;
        return change(senderId, amount, "EARN", "room:" + roomId, null, null, "pkgift:" + requestId) ? amount : 0;
    }

    /** Spend PK Coins (the PK Shop). 409 when the balance is too low; the same key twice charges once. */
    @Transactional
    public void spend(Long userId, long amount, String ref, String reason, String key) {
        if (amount <= 0) throw bad("Nothing to pay.");
        change(userId, -amount, "SPEND", ref, reason, null, key);
    }

    /** Staff grant (amount > 0) or deduct (amount < 0), with a reason, logged with their name. Returns the new balance. */
    @Transactional
    public long adminChange(Long userId, long amount, String reason, String admin) {
        if (amount == 0) throw bad("Enter a number of PK Coins.");
        if (Math.abs(amount) > 1_000_000_000L) throw bad("That is too many PK Coins at once.");
        if (reason == null || reason.isBlank()) throw bad("A reason is required.");
        Integer exists = jdbc.queryForObject("select count(*) from users where id = ?", Integer.class, userId);
        if (exists == null || exists == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        String r = reason.strip();
        change(userId, amount, amount > 0 ? "GRANT" : "DEDUCT", "admin", r.length() > 300 ? r.substring(0, 300) : r, admin,
                "admin:" + UUID.randomUUID());
        return balance(userId);
    }

    /** Marks the rows a "start everyone from zero" wrote; the PK board only counts what was earned after the last one. */
    static final String RESET_REF = "reset-all";
    /** When PK Coins were last reset for everyone (EPOCH = never); read once, then kept up to date by resetAll(). */
    private volatile Instant lastReset;

    Instant lastReset() {
        Instant r = lastReset;
        if (r == null) {
            Timestamp t = jdbc.queryForObject("select max(created_at) from pk_coin_transactions where ref = ?", Timestamp.class, RESET_REF);
            r = t == null ? Instant.EPOCH : t.toInstant();
            lastReset = r;
        }
        return r;
    }

    /**
     * Everyone starts again from zero: every balance and "earned" total goes to 0, and the PK board only counts PK Coins earned
     * from now on. Each wallet gets a DEDUCT row (by the admin, with the reason) so the history still shows what happened.
     * Returns how many people had PK Coins and how many were taken away in all.
     */
    @Transactional
    public Map<String, Object> resetAll(String reason, String admin) {
        String r = reason == null || reason.isBlank() ? "PK Coins reset: everyone starts from zero" : reason.strip();
        if (r.length() > 300) r = r.substring(0, 300);
        Instant now = Instant.now();
        long people = 0, total = 0;
        List<Map<String, Object>> wallets = jdbc.queryForList("select user_id, balance from pk_coin_wallets order by user_id for update");
        for (Map<String, Object> w : wallets) {
            long userId = ((Number) w.get("user_id")).longValue();
            long bal = ((Number) w.get("balance")).longValue();
            jdbc.update("insert into pk_coin_transactions (user_id, amount, kind, ref, reason, admin_name, balance_after, idempotency_key, created_at)"
                    + " values (?, ?, 'DEDUCT', ?, ?, ?, 0, ?, ?)", userId, -bal, RESET_REF, r, admin, RESET_REF + ":" + now.toEpochMilli() + ":" + userId, Timestamp.from(now));
            if (bal > 0) {
                people++;
                total += bal;
                afterCommit(() -> push(userId, 0, -bal, "DEDUCT"));
            }
        }
        jdbc.update("update pk_coin_wallets set balance = 0, earned = 0, updated_at = now()");
        if (!wallets.isEmpty()) afterCommit(() -> lastReset = now);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("people", people);
        m.put("removed", total);
        m.put("wallets", wallets.size());
        return m;
    }

    /** The start of a PK board period, never before the last reset. */
    private Instant from(String period) {
        Instant s = since(period);
        Instant r = lastReset();
        return r.isAfter(s) ? r : s;
    }

    /**
     * The one place a balance changes. Locks the wallet row, refuses to go below zero, and records the change with a
     * unique key: the same key a second time does nothing (false).
     */
    private boolean change(Long userId, long amount, String kind, String ref, String reason, String admin, String key) {
        jdbc.update("insert into pk_coin_wallets (user_id) values (?) on conflict do nothing", userId);
        Long bal = jdbc.queryForObject("select balance from pk_coin_wallets where user_id = ? for update", Long.class, userId);
        // Checked under the wallet lock, so two copies of the same request can't both pass
        Boolean done = jdbc.queryForObject("select exists (select 1 from pk_coin_transactions where idempotency_key = ?)", Boolean.class, key);
        if (Boolean.TRUE.equals(done)) return false;
        long next = (bal == null ? 0 : bal) + amount;
        if (next < 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Not enough PK Coins.");
        jdbc.update("update pk_coin_wallets set balance = ?, earned = earned + ?, updated_at = now() where user_id = ?",
                next, amount > 0 && ("EARN".equals(kind) || "GRANT".equals(kind)) ? amount : 0, userId);
        jdbc.update("insert into pk_coin_transactions (user_id, amount, kind, ref, reason, admin_name, balance_after, idempotency_key)"
                        + " values (?, ?, ?, ?, ?, ?, ?, ?)",
                userId, amount, kind, ref, reason, admin, next, key);
        afterCommit(() -> push(userId, next, amount, kind));
        return true;
    }

    private void push(Long userId, long balance, long change, String kind) {
        try {
            String email = jdbc.query("select email from users where id = ?", rs -> rs.next() ? rs.getString(1) : null, userId);
            if (email == null) return;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "PK_COINS");
            m.put("pkCoins", balance);
            m.put("change", change);
            m.put("kind", kind);
            Object payload = m;
            messaging.convertAndSendToUser(email, "/queue/wallet", payload);
        } catch (Exception e) {
            log.debug("PK coin push skipped: {}", e.getMessage());
        }
    }

    public long balance(Long userId) {
        Long b = jdbc.query("select balance from pk_coin_wallets where user_id = ?", rs -> rs.next() ? rs.getLong(1) : 0L, userId);
        return b == null ? 0 : b;
    }

    /** Your PK Coins: balance, everything earned, and where you stand this week, this month and overall. */
    @Transactional(readOnly = true)
    public Map<String, Object> me(Long userId) {
        Map<String, Object> m = new LinkedHashMap<>();
        long[] w = jdbc.query("select balance, earned from pk_coin_wallets where user_id = ?",
                rs -> rs.next() ? new long[]{rs.getLong(1), rs.getLong(2)} : new long[]{0, 0}, userId);
        m.put("balance", w == null ? 0 : w[0]);
        m.put("earned", w == null ? 0 : w[1]);
        for (String p : List.of("week", "month", "all")) {
            Instant since = from(p);
            long score = score(userId, since);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("score", score);
            r.put("rank", score > 0 ? rankOf(score, since) : null);
            m.put(p, r);
        }
        Settings s = settings();
        m.put("enabled", s.enabled());
        m.put("percent", s.percent());
        m.put("officialBonus", s.officialBonus());
        m.put("itemDays", s.itemDays());
        return m;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> history(Long userId, int limit) {
        return jdbc.query("select id, user_id, amount, kind, ref, reason, admin_name, balance_after, created_at from pk_coin_transactions"
                + " where user_id = ? order by id desc limit ?", (rs, i) -> txRow(rs), userId, Math.max(1, Math.min(200, limit)));
    }

    /** Staff view: the latest changes, for everyone or one user, optionally of one kind. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> transactions(Long userId, String kind, int limit) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("select t.id, t.user_id, t.amount, t.kind, t.ref, t.reason, t.admin_name, t.balance_after, t.created_at,"
                + " u.full_name, coalesce(u.display_public_id, u.public_id) as public_id from pk_coin_transactions t join users u on u.id = t.user_id where true");
        if (userId != null) {
            sql.append(" and t.user_id = ?");
            args.add(userId);
        }
        if (kind != null && !kind.isBlank()) {
            sql.append(" and t.kind = ?");
            args.add(kind.toUpperCase(Locale.ROOT));
        }
        sql.append(" order by t.id desc limit ?");
        args.add(Math.max(1, Math.min(500, limit)));
        return jdbc.query(sql.toString(), (rs, i) -> {
            Map<String, Object> t = txRow(rs);
            t.put("name", rs.getString("full_name"));
            t.put("publicId", rs.getLong("public_id"));
            return t;
        }, args.toArray());
    }

    /** Staff view: who holds the most PK Coins (or one search by name / ID). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> balances(String q, int limit) {
        String term = q == null ? "" : q.trim();
        Long num = term.matches("^\\d{1,12}$") ? Long.parseLong(term) : null;
        String like = "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return jdbc.query("select w.user_id, w.balance, w.earned, w.updated_at, u.full_name, u.avatar_url, coalesce(u.display_public_id, u.public_id) as public_id"
                        + " from pk_coin_wallets w join users u on u.id = w.user_id"
                        + " where (? = '' or u.full_name ilike ? or u.public_id = ? or u.display_public_id = ? or u.id = ?)"
                        + " order by w.balance desc, w.earned desc limit ?",
                (rs, i) -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("userId", String.valueOf(rs.getLong("user_id")));
                    r.put("name", rs.getString("full_name"));
                    r.put("avatarUrl", MediaUrls.protectedLink(rs.getString("avatar_url")));
                    r.put("publicId", rs.getLong("public_id"));
                    r.put("balance", rs.getLong("balance"));
                    r.put("earned", rs.getLong("earned"));
                    r.put("updatedAt", rs.getTimestamp("updated_at").toInstant().toString());
                    return r;
                }, term, like, num == null ? -1L : num, num == null ? -1L : num, num == null ? -1L : num, Math.max(1, Math.min(500, limit)));
    }

    /** Totals for the admin page. */
    @Transactional(readOnly = true)
    public Map<String, Object> totals() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("holders", jdbc.queryForObject("select count(*) from pk_coin_wallets where balance > 0", Long.class));
        m.put("inCirculation", jdbc.queryForObject("select coalesce(sum(balance), 0) from pk_coin_wallets", Long.class));
        m.put("earnedThisWeek", jdbc.queryForObject("select coalesce(sum(amount), 0) from pk_coin_transactions where kind = 'EARN' and created_at >= ?",
                Long.class, Timestamp.from(from("week"))));
        m.put("spentThisWeek", jdbc.queryForObject("select coalesce(-sum(amount), 0) from pk_coin_transactions where kind = 'SPEND' and created_at >= ?",
                Long.class, Timestamp.from(from("week"))));
        return m;
    }

    private static Map<String, Object> txRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("id", rs.getLong("id"));
        t.put("userId", String.valueOf(rs.getLong("user_id")));
        t.put("amount", rs.getLong("amount"));
        t.put("kind", rs.getString("kind"));
        t.put("ref", rs.getString("ref"));
        t.put("reason", rs.getString("reason"));
        t.put("admin", rs.getString("admin_name"));
        t.put("balanceAfter", rs.getLong("balance_after"));
        t.put("at", rs.getTimestamp("created_at").toInstant().toString());
        return t;
    }

    /** The PK board: PK Coins earned in the period (week = since Monday, month = since the 1st, India time; all = ever). */
    @Transactional(readOnly = true)
    public Map<String, Object> ranking(String period, Long me) {
        Instant since = from(period);
        List<Map<String, Object>> rows = jdbc.query(
                "select x.user_id, x.score, u.full_name, u.avatar_url, coalesce(u.display_public_id, u.public_id) as public_id, u.vip_active, u.vip_level, u.gender"
                        + " from (select user_id, sum(amount) as score from pk_coin_transactions where kind = 'EARN' and created_at >= ? group by user_id) x"
                        + " join users u on u.id = x.user_id order by x.score desc, x.user_id limit 50",
                (rs, i) -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("rank", i + 1);
                    r.put("userId", String.valueOf(rs.getLong("user_id")));
                    r.put("name", rs.getString("full_name"));
                    r.put("avatarUrl", MediaUrls.protectedLink(rs.getString("avatar_url")));
                    r.put("publicId", rs.getLong("public_id"));
                    r.put("gender", rs.getString("gender"));
                    r.put("vipLevel", rs.getBoolean("vip_active") ? rs.getObject("vip_level") : null);
                    r.put("score", rs.getLong("score"));
                    return r;
                }, Timestamp.from(since));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("period", normal(period));
        out.put("rows", rows);
        if (me != null) {
            long score = score(me, since);
            Map<String, Object> mine = new LinkedHashMap<>();
            mine.put("score", score);
            mine.put("rank", score > 0 ? rankOf(score, since) : null);
            out.put("me", mine);
        }
        return out;
    }

    private long score(Long userId, Instant since) {
        Long v = jdbc.queryForObject("select coalesce(sum(amount), 0) from pk_coin_transactions where user_id = ? and kind = 'EARN' and created_at >= ?",
                Long.class, userId, Timestamp.from(since));
        return v == null ? 0 : v;
    }

    private long rankOf(long score, Instant since) {
        Long above = jdbc.queryForObject("select count(*) from (select user_id from pk_coin_transactions where kind = 'EARN' and created_at >= ?"
                + " group by user_id having sum(amount) > ?) x", Long.class, Timestamp.from(since), score);
        return (above == null ? 0 : above) + 1;
    }

    static String normal(String p) {
        return "week".equals(p) || "weekly".equals(p) ? "week" : "month".equals(p) || "monthly".equals(p) ? "month" : "all";
    }

    static Instant since(String period) {
        LocalDate today = LocalDate.now(ZONE);
        return switch (normal(period)) {
            case "week" -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay(ZONE).toInstant();
            case "month" -> today.withDayOfMonth(1).atStartOfDay(ZONE).toInstant();
            default -> Instant.EPOCH;
        };
    }

    private static void afterCommit(Runnable r) {
        // After commit, off this request's DB connection (see common.tx.AfterCommit)
        com.panchayat.backend.common.tx.AfterCommit.run(r);
    }

    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }
}
