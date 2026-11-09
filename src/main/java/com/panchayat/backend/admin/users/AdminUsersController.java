package com.panchayat.backend.admin.users;

import com.panchayat.backend.common.PublicIds;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * The app's users for the admin panel (Admins and Super Admins).
 *
 * Counts: total, new, online now (has the app open: connected to live
 * updates), and active today / 7 days / 30 days. "Active" = signed in or
 * refreshed their session, joined a room, or had coins move in that time.
 * List: search by name, email, 7-digit public ID or internal ID.
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
public class AdminUsersController {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    /** Activity from the three places users leave a trace. */
    private static final String ACTIVITY =
            "select user_id, created_at as at from refresh_tokens"
                    + " union all select user_id, joined_at from voice_room_participants"
                    + " union all select user_id, created_at from coin_transactions";

    private final JdbcTemplate jdbc;
    private final SimpUserRegistry online;
    private final AccountBanService bans;

    public AdminUsersController(JdbcTemplate jdbc, SimpUserRegistry online, AccountBanService bans) {
        this.bans = bans;
        this.jdbc = jdbc;
        this.online = online;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Instant today = LocalDate.now(IST).atStartOfDay(IST).toInstant();
        Instant now = Instant.now();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", count("select count(*) from users"));
        m.put("newToday", count("select count(*) from users where created_at >= ?", today));
        m.put("new7Days", count("select count(*) from users where created_at >= ?", now.minusSeconds(7 * 86400L)));
        m.put("onlineNow", onlineEmails().size());
        m.put("inRoomsNow", count("select count(distinct user_id) from voice_room_participants where status = 'JOINED'"));
        m.put("activeToday", active(today));
        m.put("active7Days", active(now.minusSeconds(7 * 86400L)));
        m.put("active30Days", active(now.minusSeconds(30 * 86400L)));
        m.put("vipActive", count("select count(*) from users where vip_active and (vip_expires_at is null or vip_expires_at > now())"));
        m.put("profileCompleted", count("select count(*) from users where profile_completed"));
        return m;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size,
                                    @RequestParam(required = false) String q,
                                    @RequestParam(defaultValue = "all") String filter) {
        int p = Math.max(0, page);
        int s = Math.min(100, Math.max(1, size));
        List<String> where = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        Set<String> onlineNow = onlineEmails();
        if (q != null && !q.isBlank()) {
            String term = q.trim();
            Long pub = PublicIds.parse(term);
            if (pub != null) {
                where.add("u.public_id = ?");
                args.add(pub);
            } else if (term.matches("\\d{1,18}")) {
                where.add("u.id = ?");
                args.add(Long.parseLong(term));
            } else {
                String like = "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                where.add("(u.full_name ilike ? or u.email ilike ?)");
                args.add(like);
                args.add(like);
            }
        }
        switch (filter) {
            case "online" -> {
                if (onlineNow.isEmpty()) where.add("false");
                else {
                    where.add("lower(u.email) in (" + String.join(",", Collections.nCopies(onlineNow.size(), "?")) + ")");
                    args.addAll(onlineNow);
                }
            }
            case "vip" -> where.add("u.vip_active and (u.vip_expires_at is null or u.vip_expires_at > now())");
            case "new" -> where.add("u.created_at >= now() - interval '7 days'");
            case "banned" -> where.add("(u.ban_permanent or u.banned_until > now())");
            case "all" -> {
                // everyone
            }
            default -> throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "filter must be all, online, vip, new or banned");
        }
        String w = where.isEmpty() ? "" : " where " + String.join(" and ", where);
        Long total = jdbc.queryForObject("select count(*) from users u" + w, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add((long) p * s);
        List<Map<String, Object>> rows = jdbc.query(
                "select u.id, u.public_id, u.full_name, u.email, u.gender, u.avatar_url, u.level, u.created_at, u.profile_completed,"
                        + " u.vip_active, u.vip_level, u.vip_expires_at, u.ban_permanent, u.banned_until, u.ban_reason, u.charm_points,"
                        + " coalesce(w.balance, 0) as coins,"
                        + " (select max(a.at) from (" + ACTIVITY + ") a where a.user_id = u.id) as last_active,"
                        + " exists (select 1 from voice_room_participants vp where vp.user_id = u.id and vp.status = 'JOINED') as in_room"
                        + " from users u left join coin_wallets w on w.user_id = u.id" + w
                        + " order by u.created_at desc, u.id desc limit ? offset ?",
                (rs, i) -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    String email = rs.getString("email");
                    r.put("id", rs.getLong("id"));
                    r.put("publicId", rs.getLong("public_id"));
                    r.put("name", rs.getString("full_name"));
                    r.put("email", email);
                    r.put("gender", rs.getString("gender"));
                    r.put("avatarUrl", rs.getString("avatar_url"));
                    r.put("level", rs.getObject("level"));
                    r.put("joinedAt", ts(rs.getTimestamp("created_at")));
                    r.put("profileCompleted", rs.getBoolean("profile_completed"));
                    boolean vip = rs.getBoolean("vip_active");
                    Timestamp vipEnd = rs.getTimestamp("vip_expires_at");
                    r.put("vipLevel", vip && (vipEnd == null || vipEnd.toInstant().isAfter(Instant.now())) ? rs.getObject("vip_level") : null);
                    r.put("coins", rs.getLong("coins"));
                    r.put("charm", rs.getLong("charm_points"));
                    r.put("lastActiveAt", ts(rs.getTimestamp("last_active")));
                    r.put("online", email != null && onlineNow.contains(email.toLowerCase(Locale.ROOT)));
                    r.put("inRoom", rs.getBoolean("in_room"));
                    Timestamp bannedUntil = rs.getTimestamp("banned_until");
                    boolean permanent = rs.getBoolean("ban_permanent");
                    boolean banned = permanent || (bannedUntil != null && bannedUntil.toInstant().isAfter(Instant.now()));
                    r.put("banned", banned);
                    r.put("banPermanent", banned && permanent);
                    r.put("bannedUntil", banned && !permanent ? ts(bannedUntil) : null);
                    r.put("banReason", banned ? rs.getString("ban_reason") : null);
                    return r;
                }, pageArgs.toArray());
        long t = total == null ? 0 : total;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", rows);
        out.put("page", p);
        out.put("size", s);
        out.put("totalElements", t);
        out.put("totalPages", (t + s - 1) / s);
        return out;
    }

    public record RemoveRequest(Long amount, String reason) {
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.panchayat.backend.wallet.service.CoinWalletService wallet;
    @org.springframework.beans.factory.annotation.Autowired
    private com.panchayat.backend.wallet.service.WalletPushService walletPush;

    /** Take coins away from a user: the amount, or all of them when amount is empty. Super Admin only. */
    @PostMapping("/{id:[0-9]+}/remove-coins")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Map<String, Object> removeCoins(@PathVariable Long id, @RequestBody RemoveRequest body, org.springframework.security.core.Authentication auth) {
        if (count("select count(*) from users where id = ?", id) == 0) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "User not found");
        long balance = wallet.getBalance(id);
        long amount = body == null || body.amount() == null ? balance : body.amount();
        if (amount < 0) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "The amount must be 0 or more.");
        if (amount > balance) amount = balance; // can't take more than they have
        String why = body == null || body.reason() == null || body.reason().isBlank() ? "Removed by staff" : body.reason().strip();
        if (amount > 0) {
            wallet.debit(id, amount, "ADMIN_REMOVE", "admin", "admin-remove:" + java.util.UUID.randomUUID(), why);
            walletPush.pushBalance(id, "ADMIN_REMOVE", -amount);
        }
        jdbc.update("insert into admin_user_adjust (user_id, kind, amount, reason, admin_name) values (?, 'COINS', ?, ?, ?)", id, amount, why, auth == null ? null : auth.getName());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("removed", amount);
        m.put("balance", wallet.getBalance(id));
        return m;
    }

    /** Take Charm away from a user: the amount, or all of it when amount is empty. Super Admin only. */
    @PostMapping("/{id:[0-9]+}/remove-charm")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Map<String, Object> removeCharm(@PathVariable Long id, @RequestBody RemoveRequest body, org.springframework.security.core.Authentication auth) {
        Long before = jdbc.query("select charm_points from users where id = ?", rs -> rs.next() ? rs.getLong(1) : null, id);
        if (before == null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "User not found");
        long amount = body == null || body.amount() == null ? before : Math.min(body.amount(), before);
        if (amount < 0) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "The amount must be 0 or more.");
        String why = body == null || body.reason() == null || body.reason().isBlank() ? "Removed by staff" : body.reason().strip();
        jdbc.update("update users set charm_points = greatest(0, charm_points - ?) where id = ?", amount, id);
        jdbc.update("insert into admin_user_adjust (user_id, kind, amount, reason, admin_name) values (?, 'CHARM', ?, ?, ?)", id, amount, why, auth == null ? null : auth.getName());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("removed", amount);
        m.put("charm", Math.max(0, before - amount));
        return m;
    }

    public record BanRequest(Integer days, String reason) {
    }

    /** Ban an account: permanent when days is empty, otherwise a suspension for that many days. */
    @PostMapping("/{id:[0-9]+}/ban")
    public Map<String, Object> ban(@PathVariable Long id, @RequestBody BanRequest body, org.springframework.security.core.Authentication auth) {
        AccountBanService.Ban b = bans.ban(id, body.days(), body.reason(), auth == null ? null : auth.getName());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("banned", true);
        m.put("permanent", b.permanent());
        m.put("until", b.until());
        return m;
    }

    @PostMapping("/{id:[0-9]+}/unban")
    public Map<String, Object> unban(@PathVariable Long id, org.springframework.security.core.Authentication auth) {
        bans.unban(id, auth == null ? null : auth.getName());
        return Map.of("banned", false);
    }

    /** People with the app open: their live connection's name is their email. */
    private Set<String> onlineEmails() {
        Set<String> out = new HashSet<>();
        online.getUsers().forEach(u -> {
            String n = u.getName();
            if (n != null && n.contains("@")) out.add(n.toLowerCase(Locale.ROOT));
        });
        return out;
    }

    private long active(Instant since) {
        Long n = jdbc.queryForObject("select count(distinct user_id) from (" + ACTIVITY + ") a where a.at >= ?", Long.class, Timestamp.from(since));
        return n == null ? 0 : n;
    }

    private long count(String sql, Object... args) {
        Object[] a = Arrays.stream(args).map(x -> x instanceof Instant i ? Timestamp.from(i) : x).toArray();
        Long n = jdbc.queryForObject(sql, Long.class, a);
        return n == null ? 0 : n;
    }

    private static Instant ts(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
