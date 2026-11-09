package com.panchayat.backend.parivar;

import com.panchayat.backend.admin.media.MediaUrls;
import com.panchayat.backend.wallet.service.CoinWalletService;
import com.panchayat.backend.wallet.service.WalletPushService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/**
 * Parivar (family). All the rules come from the admin-managed tables (parivar_settings, parivar_levels,
 * parivar_permissions); nothing about prices, limits or permissions is fixed in this code.
 *
 * Money rules: creating a Parivar costs coins from the person's own wallet (the Rs. 499 is only a reference converted
 * to coins, never a real payment). Donations move coins from a wallet to the Parivar's treasury. Every coin movement is
 * done in one transaction and is safe to retry (idempotency keys).
 */
@Service
public class ParivarService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    /** Public Parivar ID shown to people = 4,000,000 + internal id. */
    public static final long CODE_BASE = 4_000_000L;
    public static final List<String> PERMS = List.of("EDIT_PROFILE", "EDIT_ANNOUNCEMENT", "ACCEPT_APPLICATIONS", "REMOVE_MEMBERS", "APPOINT_DEPUTY", "APPOINT_ADMIN", "MANAGE_TREASURY", "MANAGE_SETTINGS");
    private static final List<String> ROLE_RANK = List.of("MEMBER", "ADMIN", "DEPUTY", "LEADER");

    private final JdbcTemplate jdbc;
    private final CoinWalletService wallet;
    private final WalletPushService walletPush;

    public ParivarService(JdbcTemplate jdbc, CoinWalletService wallet, WalletPushService walletPush) {
        this.jdbc = jdbc;
        this.wallet = wallet;
        this.walletPush = walletPush;
    }

    /* ======================= settings and creation price ======================= */

    public Map<String, Object> settings() {
        return jdbc.queryForMap("select * from parivar_settings where id = 1");
    }

    private static boolean b(Object o) {
        return Boolean.TRUE.equals(o);
    }

    private static long l(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }

    /** Coins for one rupee: the admin's number, else worked out from the cheapest active coin pack. Null if neither exists. */
    public BigDecimal coinsPerInr(Map<String, Object> s) {
        if (s.get("coins_per_inr") != null) return (BigDecimal) s.get("coins_per_inr");
        List<BigDecimal> r = jdbc.query("select coins, price_in_rupees from coin_packs where active and price_in_rupees > 0 order by price_in_rupees asc limit 1",
                (rs, i) -> BigDecimal.valueOf(rs.getLong(1)).divide(rs.getBigDecimal(2), 4, RoundingMode.HALF_UP));
        return r.isEmpty() ? null : r.get(0);
    }

    /** The coins a new Parivar costs right now; null when it can't be worked out (set it in the admin panel). */
    public Long requiredCoins(Map<String, Object> s) {
        if ("MANUAL".equals(s.get("creation_mode"))) return l(s.get("manual_coins"));
        BigDecimal per = coinsPerInr(s);
        if (per == null) return null;
        return ((BigDecimal) s.get("creation_inr")).multiply(per).setScale(0, RoundingMode.CEILING).longValueExact();
    }

    /** What the Create screen needs: the price in coins, the charm needed, what the person has, and what is missing. */
    public Map<String, Object> config(Long userId) {
        Map<String, Object> s = settings();
        Long coins = requiredCoins(s);
        long myCoins = wallet.getBalance(userId);
        long myCharm = l(jdbc.queryForObject("select charm_points from users where id = ?", Long.class, userId));
        long needCharm = l(s.get("required_charm"));
        Long mine = myParivarId(userId);
        List<String> problems = new ArrayList<>();
        if (!b(s.get("enabled"))) problems.add("Parivar is not open yet.");
        else if (!b(s.get("creation_open"))) problems.add("New Parivars can't be created right now.");
        if (b(s.get("one_per_user")) && mine != null) problems.add("You are already in a Parivar.");
        if (coins == null) problems.add("The creation price isn't set yet.");
        else if (myCoins < coins) problems.add("You need " + (coins - myCoins) + " more coins.");
        if (myCharm < needCharm) problems.add("You need " + (needCharm - myCharm) + " more Charm.");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", b(s.get("enabled")));
        m.put("creationOpen", b(s.get("creation_open")));
        m.put("mode", s.get("creation_mode"));
        m.put("creationInr", s.get("creation_inr"));
        m.put("coinsPerInr", coinsPerInr(s));
        m.put("requiredCoins", coins);
        m.put("requiredCharm", needCharm);
        m.put("consumeCharm", b(s.get("consume_charm")));
        m.put("myCoins", myCoins);
        m.put("myCharm", myCharm);
        m.put("myParivarId", mine == null ? null : String.valueOf(mine));
        m.put("minDonation", l(s.get("min_donation")));
        m.put("canCreate", problems.isEmpty());
        m.put("problems", problems);
        return m;
    }

    /* ======================= small helpers ======================= */

    private void lockUser(Long userId) {
        jdbc.queryForObject("select pg_advisory_xact_lock(?)", Object.class, 7_000_000_000L + userId);
    }

    private Long myParivarId(Long userId) {
        List<Long> l = jdbc.queryForList("select m.parivar_id from parivar_members m join parivars p on p.id = m.parivar_id where m.user_id = ? and p.status = 'ACTIVE'", Long.class, userId);
        return l.isEmpty() ? null : l.get(0);
    }

    private String roleOf(Long parivarId, Long userId) {
        List<String> l = jdbc.queryForList("select role from parivar_members where parivar_id = ? and user_id = ?", String.class, parivarId, userId);
        return l.isEmpty() ? null : l.get(0);
    }

    private boolean can(Long parivarId, Long userId, String perm) {
        String role = roleOf(parivarId, userId);
        if (role == null) return false;
        if (role.equals("LEADER")) return true;
        List<Boolean> a = jdbc.queryForList("select allowed from parivar_permissions where role = ? and perm = ?", Boolean.class, role, perm);
        return !a.isEmpty() && Boolean.TRUE.equals(a.get(0));
    }

    private void require(Long parivarId, Long userId, String perm) {
        if (!can(parivarId, userId, perm)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You don't have permission to do this in this Parivar.");
    }

    private static int rank(String role) {
        return ROLE_RANK.indexOf(role);
    }

    private Map<String, Object> activeParivar(Long id) {
        List<Map<String, Object>> l = jdbc.queryForList("select * from parivars where id = ? and status = 'ACTIVE'", id);
        if (l.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That Parivar doesn't exist.");
        return l.get(0);
    }

    private int capacity(int level) {
        Integer c = jdbc.query("select capacity from parivar_levels where level = ?", rs -> rs.next() ? rs.getInt(1) : null, level);
        return c == null ? 20 : c;
    }

    private long memberCount(Long parivarId) {
        return l(jdbc.queryForObject("select count(*) from parivar_members where parivar_id = ?", Long.class, parivarId));
    }

    private Timestamp weekStart() {
        return Timestamp.from(LocalDate.now(IST).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay(IST).toInstant());
    }

    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }

    private static ResponseStatusException conflict(String m) {
        return new ResponseStatusException(HttpStatus.CONFLICT, m);
    }

    /* ======================= create ======================= */

    public record CreateRequest(String name, String sign, String emblem, String color, String intro, String joinMode) {
    }

    @Transactional
    public Map<String, Object> create(Long userId, String key, CreateRequest r) {
        if (key == null || key.isBlank() || key.length() > 60) throw bad("A request key is required.");
        String createKey = userId + ":" + key.strip();
        // A retry of a creation that already worked: the same Parivar, no second charge
        List<Long> done = jdbc.queryForList("select id from parivars where create_key = ?", Long.class, createKey);
        if (!done.isEmpty()) return profile(userId, done.get(0));

        lockUser(userId);
        Map<String, Object> s = settings();
        if (!b(s.get("enabled"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Parivar is not open yet.");
        if (!b(s.get("creation_open"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "New Parivars can't be created right now.");
        if (b(s.get("one_per_user")) && myParivarId(userId) != null) throw conflict("You are already in a Parivar. Leave it first.");

        String name = r == null || r.name() == null ? "" : r.name().strip().replaceAll("\\s+", " ");
        String sign = r == null || r.sign() == null ? "" : r.sign().strip().toUpperCase(Locale.ROOT);
        if (name.length() < 3 || name.length() > 30) throw bad("The name must be 3 to 30 letters.");
        if (!sign.matches("[A-Z0-9]{2,4}")) throw bad("The sign must be 2 to 4 letters or numbers.");
        String joinMode = r.joinMode() != null && r.joinMode().equalsIgnoreCase("APPROVAL") ? "APPROVAL" : "OPEN";
        String emblem = r.emblem() == null || r.emblem().isBlank() ? "🪔" : r.emblem().strip();
        String color = r.color() != null && r.color().matches("#[0-9a-fA-F]{6}") ? r.color() : "#e7bc72";
        String intro = r.intro() == null || r.intro().isBlank() ? null : r.intro().strip().substring(0, Math.min(300, r.intro().strip().length()));
        if (jdbc.queryForObject("select count(*) from parivars where status = 'ACTIVE' and (lower(name) = lower(?) or lower(sign) = lower(?))", Long.class, name, sign) > 0)
            throw conflict("That name or sign is already taken.");

        // Charm needed first, then the coins
        long needCharm = l(s.get("required_charm"));
        if (needCharm > 0) {
            Long have = jdbc.queryForObject("select charm_points from users where id = ? for update", Long.class, userId);
            if (have == null || have < needCharm) throw new ResponseStatusException(HttpStatus.CONFLICT, "You need " + needCharm + " Charm to create a Parivar.");
            if (b(s.get("consume_charm"))) jdbc.update("update users set charm_points = charm_points - ? where id = ?", needCharm, userId);
        }
        Long coins = requiredCoins(s);
        if (coins == null) throw conflict("The creation price isn't set yet. Please try later.");
        if (coins > 0) {
            try {
                wallet.debit(userId, coins, "PARIVAR_CREATE", "parivar", "pv-create:" + createKey, "Created Parivar " + name);
            } catch (IllegalStateException e) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Not enough coins. Creating a Parivar costs " + coins + " coins.");
            }
        }
        Long id = jdbc.queryForObject("insert into parivars (name, sign, emblem, color, intro, leader_id, join_mode, create_key) values (?, ?, ?, ?, ?, ?, ?, ?) returning id",
                Long.class, name, sign, emblem, color, intro, userId, joinMode, createKey);
        jdbc.update("insert into parivar_members (parivar_id, user_id, role) values (?, ?, 'LEADER')", id, userId);
        activity(id, userId, null, "CREATED", 0, 0, "created the Parivar", "created:" + id);
        if (coins > 0) walletPush.pushBalance(userId, "PARIVAR_CREATE", -coins);
        return profile(userId, id);
    }

    /* ======================= discover ======================= */

    public Map<String, Object> list(Long userId, String q, String sort, int page, int size) {
        int p = Math.max(0, page);
        int sz = Math.min(50, Math.max(1, size));
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" where p.status = 'ACTIVE'");
        if (q != null && !q.isBlank()) {
            String t = q.trim();
            if (t.matches("\\d{1,9}")) {
                long n = Long.parseLong(t);
                where.append(" and (p.id = ? or p.id = ?)");
                args.add(n);
                args.add(n - CODE_BASE);
            } else {
                String like = "%" + t.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                where.append(" and (p.name ilike ? or p.sign ilike ?)");
                args.add(like);
                args.add(like);
            }
        }
        String order = switch (sort == null ? "popular" : sort) {
            case "top" -> "p.exp desc, p.id";
            case "new" -> "p.id desc";
            case "weekly" -> "week_exp desc, p.id";
            default -> "members desc, p.exp desc, p.id";
        };
        Timestamp ws = weekStart();
        String base = "select p.id, p.name, p.sign, p.emblem, p.color, p.logo_url, p.intro, p.level, p.exp, p.join_mode,"
                + " (select count(*) from parivar_members m where m.parivar_id = p.id) as members,"
                + " coalesce((select sum(a.exp) from parivar_activity a where a.parivar_id = p.id and a.created_at >= ?), 0) as week_exp,"
                + " (select l.capacity from parivar_levels l where l.level = p.level) as capacity,"
                + " (select a.status from parivar_applications a where a.parivar_id = p.id and a.user_id = ? and a.status = 'PENDING') as my_application"
                + " from parivars p" + where;
        List<Object> all = new ArrayList<>(List.of(ws, userId));
        all.addAll(args);
        Long total = jdbc.queryForObject("select count(*) from parivars p" + where, Long.class, args.toArray());
        all.add(sz);
        all.add((long) p * sz);
        List<Map<String, Object>> rows = jdbc.query(base + " order by " + order + " limit ? offset ?", (rs, i) -> card(rs), all.toArray());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", rows);
        out.put("page", p);
        out.put("size", sz);
        out.put("totalElements", total == null ? 0 : total);
        return out;
    }

    private Map<String, Object> card(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        long id = rs.getLong("id");
        m.put("id", String.valueOf(id));
        m.put("code", CODE_BASE + id);
        m.put("name", rs.getString("name"));
        m.put("sign", rs.getString("sign"));
        m.put("emblem", rs.getString("emblem"));
        m.put("color", rs.getString("color"));
        m.put("logoUrl", MediaUrls.link(rs.getString("logo_url")));
        m.put("intro", rs.getString("intro"));
        m.put("level", rs.getInt("level"));
        m.put("exp", rs.getLong("exp"));
        m.put("weekExp", rs.getLong("week_exp"));
        m.put("joinMode", rs.getString("join_mode"));
        m.put("members", rs.getLong("members"));
        m.put("capacity", rs.getInt("capacity"));
        m.put("myApplication", rs.getString("my_application"));
        return m;
    }

    /** The Parivar the signed-in person is in (or has applied to). */
    public Map<String, Object> mine(Long userId) {
        Long id = myParivarId(userId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("parivarId", id == null ? null : String.valueOf(id));
        m.put("role", id == null ? null : roleOf(id, userId));
        List<Long> pending = jdbc.queryForList("select parivar_id from parivar_applications where user_id = ? and status = 'PENDING' limit 1", Long.class, userId);
        m.put("appliedTo", pending.isEmpty() ? null : String.valueOf(pending.get(0)));
        m.put("enabled", b(settings().get("enabled")));
        return m;
    }

    /** A person's Parivar tag for their name (sign, name, emblem), or null. Used for the tag next to names. */
    public Map<String, Object> tagOf(Long userId) {
        List<Map<String, Object>> l = jdbc.queryForList("select p.id, p.sign, p.name, p.emblem, p.color from parivar_members m join parivars p on p.id = m.parivar_id where m.user_id = ? and p.status = 'ACTIVE'", userId);
        if (l.isEmpty()) return null;
        Map<String, Object> r = l.get(0);
        return Map.of("id", String.valueOf(r.get("id")), "sign", r.get("sign"), "name", r.get("name"), "emblem", r.get("emblem"), "color", r.get("color"));
    }

    /* ======================= profile ======================= */

    public Map<String, Object> profile(Long userId, Long id) {
        Map<String, Object> p = activeParivar(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(id));
        m.put("code", CODE_BASE + id);
        m.put("name", p.get("name"));
        m.put("sign", p.get("sign"));
        m.put("emblem", p.get("emblem"));
        m.put("color", p.get("color"));
        m.put("logoUrl", MediaUrls.link((String) p.get("logo_url")));
        m.put("intro", p.get("intro"));
        m.put("announcement", p.get("announcement"));
        m.put("joinMode", p.get("join_mode"));
        int level = ((Number) p.get("level")).intValue();
        long exp = l(p.get("exp"));
        m.put("level", level);
        m.put("exp", exp);
        Long next = jdbc.query("select min(exp_needed) from parivar_levels where exp_needed > ?", rs -> rs.next() ? (Long) rs.getObject(1) : null, exp);
        m.put("nextLevelExp", next);
        m.put("capacity", capacity(level));
        m.put("funds", l(p.get("funds")));
        m.put("createdAt", ((Timestamp) p.get("created_at")).toInstant().toString());
        Timestamp ws = weekStart();
        m.put("weekExp", l(jdbc.queryForObject("select coalesce(sum(exp), 0) from parivar_activity where parivar_id = ? and created_at >= ?", Long.class, id, ws)));
        List<Map<String, Object>> members = jdbc.query(
                "select m.user_id, m.role, m.contribution, m.joined_at, u.full_name, u.avatar_url, u.public_id from parivar_members m join users u on u.id = m.user_id where m.parivar_id = ?"
                        + " order by case m.role when 'LEADER' then 0 when 'DEPUTY' then 1 when 'ADMIN' then 2 else 3 end, m.contribution desc, m.joined_at",
                (rs, i) -> {
                    Map<String, Object> x = new LinkedHashMap<>();
                    x.put("userId", String.valueOf(rs.getLong("user_id")));
                    x.put("name", rs.getString("full_name"));
                    x.put("avatarUrl", MediaUrls.link(rs.getString("avatar_url")));
                    x.put("publicId", rs.getLong("public_id"));
                    x.put("role", rs.getString("role"));
                    x.put("contribution", rs.getLong("contribution"));
                    x.put("joinedAt", rs.getTimestamp("joined_at").toInstant().toString());
                    return x;
                }, id);
        m.put("members", members);
        m.put("memberCount", members.size());
        String myRole = roleOf(id, userId);
        m.put("myRole", myRole);
        List<String> myPerms = new ArrayList<>();
        for (String perm : PERMS) if (can(id, userId, perm)) myPerms.add(perm);
        m.put("myPerms", myPerms);
        List<String> pend = jdbc.queryForList("select status from parivar_applications where parivar_id = ? and user_id = ? and status = 'PENDING'", String.class, id, userId);
        m.put("myApplication", pend.isEmpty() ? null : pend.get(0));
        m.put("pendingApplications", myPerms.contains("ACCEPT_APPLICATIONS") ? jdbc.queryForObject("select count(*) from parivar_applications where parivar_id = ? and status = 'PENDING'", Long.class, id) : 0);
        List<Map<String, Object>> rk = rankRow(id);
        m.put("weekRank", rk.isEmpty() ? null : rk.get(0).get("week_rank"));
        m.put("totalRank", rk.isEmpty() ? null : rk.get(0).get("total_rank"));
        if (myRole != null) m.put("activity", feed(id, 20));
        return m;
    }

    private List<Map<String, Object>> rankRow(Long id) {
        return jdbc.queryForList("select * from (select p.id, rank() over (order by p.exp desc, p.id) as total_rank,"
                + " rank() over (order by coalesce(w.s, 0) desc, p.id) as week_rank from parivars p"
                + " left join (select parivar_id, sum(exp) as s from parivar_activity where created_at >= ?" + giftFilter() + " group by parivar_id) w on w.parivar_id = p.id"
                + " where p.status = 'ACTIVE') r where r.id = ?", weekStart(), id);
    }

    private String giftFilter() {
        return b(settings().get("gift_counts_for_ranking")) ? "" : " and kind <> 'GIFT'";
    }

    /* ======================= membership ======================= */

    @Transactional
    public Map<String, Object> join(Long userId, Long id) {
        lockUser(userId);
        Map<String, Object> s = settings();
        if (!b(s.get("enabled"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Parivar is not open yet.");
        Map<String, Object> p = activeParivar(id);
        if (roleOf(id, userId) != null) throw conflict("You are already in this Parivar.");
        if (b(s.get("one_per_user")) && myParivarId(userId) != null) throw conflict("You are already in a Parivar. Leave it first.");
        if ("OPEN".equals(p.get("join_mode"))) {
            addMember(id, userId, p);
            return profile(userId, id);
        }
        try {
            jdbc.update("insert into parivar_applications (parivar_id, user_id) values (?, ?)", id, userId);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw conflict("You have already applied to this Parivar.");
        }
        return profile(userId, id);
    }

    private void addMember(Long id, Long userId, Map<String, Object> p) {
        int level = ((Number) p.get("level")).intValue();
        if (memberCount(id) >= capacity(level)) throw conflict("This Parivar is full.");
        jdbc.update("insert into parivar_members (parivar_id, user_id, role) values (?, ?, 'MEMBER')", id, userId);
        activity(id, userId, null, "JOINED", 0, 0, "joined the Parivar", null);
    }

    @Transactional
    public Map<String, Object> withdraw(Long userId, Long id) {
        jdbc.update("update parivar_applications set status = 'WITHDRAWN', decided_at = now() where parivar_id = ? and user_id = ? and status = 'PENDING'", id, userId);
        return profile(userId, id);
    }

    public List<Map<String, Object>> applications(Long userId, Long id) {
        activeParivar(id);
        require(id, userId, "ACCEPT_APPLICATIONS");
        return jdbc.query("select a.id, a.user_id, a.created_at, u.full_name, u.avatar_url, u.public_id from parivar_applications a join users u on u.id = a.user_id where a.parivar_id = ? and a.status = 'PENDING' order by a.id",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", String.valueOf(rs.getLong("id")));
                    m.put("userId", String.valueOf(rs.getLong("user_id")));
                    m.put("name", rs.getString("full_name"));
                    m.put("avatarUrl", MediaUrls.link(rs.getString("avatar_url")));
                    m.put("publicId", rs.getLong("public_id"));
                    m.put("at", rs.getTimestamp("created_at").toInstant().toString());
                    return m;
                }, id);
    }

    @Transactional
    public Map<String, Object> decide(Long actor, Long id, Long appId, boolean accept) {
        Map<String, Object> p = activeParivar(id);
        require(id, actor, "ACCEPT_APPLICATIONS");
        List<Map<String, Object>> a = jdbc.queryForList("select user_id, status from parivar_applications where id = ? and parivar_id = ? for update", appId, id);
        if (a.isEmpty() || !"PENDING".equals(a.get(0).get("status"))) throw conflict("That application was already answered.");
        long applicant = l(a.get(0).get("user_id"));
        if (!accept) {
            jdbc.update("update parivar_applications set status = 'REJECTED', decided_by = ?, decided_at = now() where id = ?", actor, appId);
            return Map.of("accepted", false);
        }
        lockUser(applicant);
        if (b(settings().get("one_per_user")) && myParivarId(applicant) != null) {
            jdbc.update("update parivar_applications set status = 'REJECTED', decided_by = ?, decided_at = now() where id = ?", actor, appId);
            throw conflict("They already joined another Parivar.");
        }
        addMember(id, applicant, p);
        jdbc.update("update parivar_applications set status = 'ACCEPTED', decided_by = ?, decided_at = now() where id = ?", actor, appId);
        return Map.of("accepted", true);
    }

    @Transactional
    public Map<String, Object> leave(Long userId) {
        lockUser(userId);
        Long id = myParivarId(userId);
        if (id == null) throw conflict("You are not in a Parivar.");
        String role = roleOf(id, userId);
        if ("LEADER".equals(role)) {
            if (memberCount(id) > 1) throw conflict("Make someone else the leader before you leave.");
            jdbc.update("update parivars set status = 'DISBANDED' where id = ?", id);
        }
        jdbc.update("delete from parivar_members where parivar_id = ? and user_id = ?", id, userId);
        return Map.of("left", true);
    }

    @Transactional
    public Map<String, Object> removeMember(Long actor, Long id, Long target) {
        activeParivar(id);
        require(id, actor, "REMOVE_MEMBERS");
        String tr = roleOf(id, target);
        if (tr == null) throw conflict("They are not in this Parivar.");
        if (rank(roleOf(id, actor)) <= rank(tr)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only remove people below your role.");
        jdbc.update("delete from parivar_members where parivar_id = ? and user_id = ?", id, target);
        activity(id, actor, target, "REMOVED", 0, 0, "removed a member", null);
        return profile(actor, id);
    }

    public record RoleRequest(String role) {
    }

    @Transactional
    public Map<String, Object> setRole(Long actor, Long id, Long target, String role) {
        Map<String, Object> p = activeParivar(id);
        String r = role == null ? "" : role.toUpperCase(Locale.ROOT);
        if (!ROLE_RANK.contains(r)) throw bad("The role must be LEADER, DEPUTY, ADMIN or MEMBER.");
        String actorRole = roleOf(id, actor);
        String cur = roleOf(id, target);
        if (cur == null) throw conflict("They are not in this Parivar.");
        if (actor.equals(target)) throw bad("You can't change your own role.");
        if ("LEADER".equals(r)) {
            if (!"LEADER".equals(actorRole)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the leader can hand over leadership.");
            jdbc.update("update parivar_members set role = 'DEPUTY' where parivar_id = ? and user_id = ?", id, actor);
            jdbc.update("update parivar_members set role = 'LEADER' where parivar_id = ? and user_id = ?", id, target);
            jdbc.update("update parivars set leader_id = ? where id = ?", target, id);
            activity(id, actor, target, "ROLE", 0, 0, "handed over leadership", null);
            return profile(actor, id);
        }
        if (rank(actorRole) <= rank(cur)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only change people below your role.");
        if ("DEPUTY".equals(r)) require(id, actor, "APPOINT_DEPUTY");
        else if ("ADMIN".equals(r)) require(id, actor, "APPOINT_ADMIN");
        else require(id, actor, "DEPUTY".equals(cur) ? "APPOINT_DEPUTY" : "APPOINT_ADMIN");
        if (rank(r) >= rank(actorRole)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only give roles below your own.");
        int level = ((Number) p.get("level")).intValue();
        if ("DEPUTY".equals(r) || "ADMIN".equals(r)) {
            Integer cap = jdbc.query("select " + ("DEPUTY".equals(r) ? "deputy_cap" : "admin_cap") + " from parivar_levels where level = ?", rs -> rs.next() ? rs.getInt(1) : 0, level);
            long have = l(jdbc.queryForObject("select count(*) from parivar_members where parivar_id = ? and role = ?", Long.class, id, r));
            if (!r.equals(cur) && have >= (cap == null ? 0 : cap)) throw conflict("This Parivar's level allows only " + cap + " " + r.toLowerCase(Locale.ROOT) + "s. Level up for more.");
        }
        jdbc.update("update parivar_members set role = ? where parivar_id = ? and user_id = ?", r, id, target);
        activity(id, actor, target, "ROLE", 0, 0, "changed a member's role to " + r.toLowerCase(Locale.ROOT), null);
        return profile(actor, id);
    }

    public record UpdateRequest(String intro, String announcement, String joinMode, String emblem, String color) {
    }

    @Transactional
    public Map<String, Object> update(Long actor, Long id, UpdateRequest r) {
        activeParivar(id);
        if (r.announcement() != null) {
            require(id, actor, "EDIT_ANNOUNCEMENT");
            String a = r.announcement().strip();
            jdbc.update("update parivars set announcement = ? where id = ?", a.isEmpty() ? null : a.substring(0, Math.min(500, a.length())), id);
        }
        if (r.intro() != null || r.joinMode() != null || r.emblem() != null || r.color() != null) {
            require(id, actor, "EDIT_PROFILE");
            if (r.intro() != null) jdbc.update("update parivars set intro = ? where id = ?", r.intro().isBlank() ? null : r.intro().strip().substring(0, Math.min(300, r.intro().strip().length())), id);
            if (r.joinMode() != null) jdbc.update("update parivars set join_mode = ? where id = ?", r.joinMode().equalsIgnoreCase("APPROVAL") ? "APPROVAL" : "OPEN", id);
            if (r.emblem() != null && !r.emblem().isBlank()) jdbc.update("update parivars set emblem = ? where id = ?", r.emblem().strip(), id);
            if (r.color() != null && r.color().matches("#[0-9a-fA-F]{6}")) jdbc.update("update parivars set color = ? where id = ?", r.color(), id);
        }
        return profile(actor, id);
    }

    /* ======================= treasury ======================= */

    @Transactional
    public Map<String, Object> donate(Long userId, Long id, long amount, String key) {
        if (key == null || key.isBlank() || key.length() > 60) throw bad("A request key is required.");
        Map<String, Object> s = settings();
        activeParivar(id);
        if (roleOf(id, userId) == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only members can donate.");
        long min = l(s.get("min_donation"));
        if (amount < min) throw bad("The smallest donation is " + min + " coins.");
        if (amount > 100_000_000L) throw bad("That is too much for one donation.");
        String ref = "donate:" + userId + ":" + key.strip();
        // A retry of a donation that already went through: nothing more is taken
        if (l(jdbc.queryForObject("select count(*) from parivar_funds_ledger where ref = ?", Long.class, ref)) > 0) return profile(userId, id);
        try {
            wallet.debit(userId, amount, "PARIVAR_DONATION", "parivar:" + id, "pv-" + ref, "Donation to Parivar " + id);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Not enough coins.");
        }
        Long after = jdbc.queryForObject("update parivars set funds = funds + ? where id = ? returning funds", Long.class, amount, id);
        jdbc.update("insert into parivar_funds_ledger (parivar_id, user_id, delta, balance_after, kind, ref) values (?, ?, ?, ?, 'DONATION', ?)", id, userId, amount, after, ref);
        long per = l(s.get("donation_coins_per_exp"));
        long exp = per > 0 ? amount / per : 0;
        activity(id, userId, null, "DONATION", exp, amount, "donated " + amount + " coins to the Parivar", ref);
        if (exp > 0) addExp(id, userId, exp);
        walletPush.pushBalance(userId, "PARIVAR_DONATION", -amount);
        return profile(userId, id);
    }

    public List<Map<String, Object>> fundsHistory(Long userId, Long id) {
        activeParivar(id);
        if (roleOf(id, userId) == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only members can see the treasury.");
        return jdbc.query("select f.id, f.user_id, f.delta, f.balance_after, f.kind, f.created_at, u.full_name from parivar_funds_ledger f left join users u on u.id = f.user_id where f.parivar_id = ? order by f.id desc limit 50",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", String.valueOf(rs.getLong("id")));
                    m.put("name", rs.getString("full_name"));
                    m.put("delta", rs.getLong("delta"));
                    m.put("balance", rs.getLong("balance_after"));
                    m.put("kind", rs.getString("kind"));
                    m.put("at", rs.getTimestamp("created_at").toInstant().toString());
                    return m;
                }, id);
    }

    /* ======================= activity ======================= */

    private void activity(Long parivarId, Long userId, Long otherId, String kind, long exp, long coins, String label, String ref) {
        jdbc.update("insert into parivar_activity (parivar_id, user_id, other_id, kind, exp, coins, label, ref) values (?, ?, ?, ?, ?, ?, ?, ?) on conflict do nothing", parivarId, userId, otherId, kind, exp, coins, label, ref);
    }

    private List<Map<String, Object>> feed(Long id, int limit) {
        return jdbc.query("select a.id, a.kind, a.exp, a.coins, a.label, a.created_at, u.full_name as who, u.avatar_url as who_avatar, o.full_name as other from parivar_activity a"
                        + " left join users u on u.id = a.user_id left join users o on o.id = a.other_id where a.parivar_id = ? order by a.id desc limit ?",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", String.valueOf(rs.getLong("id")));
                    m.put("kind", rs.getString("kind"));
                    m.put("exp", rs.getLong("exp"));
                    m.put("coins", rs.getLong("coins"));
                    m.put("text", rs.getString("label"));
                    m.put("who", rs.getString("who"));
                    m.put("whoAvatar", MediaUrls.link(rs.getString("who_avatar")));
                    m.put("other", rs.getString("other"));
                    m.put("at", rs.getTimestamp("created_at").toInstant().toString());
                    return m;
                }, id, limit);
    }

    public List<Map<String, Object>> activityFeed(Long userId, Long id) {
        activeParivar(id);
        if (roleOf(id, userId) == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only members can see the activity.");
        return feed(id, 50);
    }

    /** Adds EXP to the Parivar and the member's contribution, and levels the Parivar up when it passes a threshold. */
    private void addExp(Long id, Long userId, long exp) {
        if (exp <= 0) return;
        Long total = jdbc.queryForObject("update parivars set exp = exp + ? where id = ? returning exp", Long.class, exp, id);
        jdbc.update("update parivar_members set contribution = contribution + ? where parivar_id = ? and user_id = ?", exp, id, userId);
        Integer lvl = jdbc.query("select max(level) from parivar_levels where exp_needed <= ?", rs -> rs.next() ? (Integer) rs.getObject(1) : null, total);
        Integer cur = jdbc.queryForObject("select level from parivars where id = ?", Integer.class, id);
        if (lvl != null && cur != null && lvl > cur) {
            jdbc.update("update parivars set level = ? where id = ?", lvl, id);
            activity(id, userId, null, "LEVEL", 0, 0, "The Parivar reached level " + lvl, null);
        }
    }

    /** A gift between two members of the same Parivar adds activity (coins per EXP, daily cap per person: both are settings). */
    @Transactional
    public void onGift(Long senderId, Long receiverId, String giftId, int count, long coins, String requestId) {
        if (senderId == null || receiverId == null || senderId.equals(receiverId) || coins <= 0) return;
        List<Long> same = jdbc.queryForList("select a.parivar_id from parivar_members a join parivar_members b on b.parivar_id = a.parivar_id join parivars p on p.id = a.parivar_id"
                + " where a.user_id = ? and b.user_id = ? and p.status = 'ACTIVE'", Long.class, senderId, receiverId);
        if (same.isEmpty()) return;
        Long id = same.get(0);
        Map<String, Object> s = settings();
        if (!b(s.get("enabled"))) return;
        long per = Math.max(1, l(s.get("gift_coins_per_exp")));
        long exp = coins / per;
        long cap = l(s.get("gift_user_daily_cap"));
        Timestamp today = Timestamp.from(LocalDate.now(IST).atStartOfDay(IST).toInstant());
        long done = l(jdbc.queryForObject("select coalesce(sum(exp), 0) from parivar_activity where parivar_id = ? and user_id = ? and kind = 'GIFT' and created_at >= ?", Long.class, id, senderId, today));
        exp = Math.max(0, Math.min(exp, cap - done));
        String name = jdbc.query("select name from gifts where id = ?", rs -> rs.next() ? rs.getString(1) : giftId, giftId);
        int n = jdbc.update("insert into parivar_activity (parivar_id, user_id, other_id, kind, exp, coins, label, ref) values (?, ?, ?, 'GIFT', ?, ?, ?, ?) on conflict do nothing",
                id, senderId, receiverId, exp, coins, "sent " + (count > 1 ? count + " x " : "") + name, "gift:" + requestId);
        if (n > 0 && exp > 0) addExp(id, senderId, exp);
    }

    /* ======================= ranking ======================= */

    public Map<String, Object> ranking(Long userId, String period) {
        boolean weekly = "weekly".equalsIgnoreCase(period);
        String score = weekly ? "coalesce(w.s, 0)" : "p.exp";
        List<Object> args = new ArrayList<>();
        String join = "";
        if (weekly) {
            join = " left join (select parivar_id, sum(exp) as s from parivar_activity where created_at >= ?" + giftFilter() + " group by parivar_id) w on w.parivar_id = p.id";
            args.add(weekStart());
        }
        String sql = "select * from (select p.id, p.name, p.sign, p.emblem, p.color, p.logo_url, p.level, " + score + " as score,"
                + " rank() over (order by " + score + " desc, p.id) as rnk,"
                + " (select count(*) from parivar_members m where m.parivar_id = p.id) as members from parivars p" + join + " where p.status = 'ACTIVE') r";
        List<Map<String, Object>> top = jdbc.query(sql + " order by rnk limit 50", (rs, i) -> rankCard(rs), args.toArray());
        Long mine = myParivarId(userId);
        Map<String, Object> me = null;
        if (mine != null) {
            List<Object> a2 = new ArrayList<>(args);
            a2.add(mine);
            List<Map<String, Object>> l = jdbc.query(sql + " where r.id = ?", (rs, i) -> rankCard(rs), a2.toArray());
            me = l.isEmpty() ? null : l.get(0);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("period", weekly ? "weekly" : "total");
        out.put("rows", top);
        out.put("mine", me);
        return out;
    }

    private Map<String, Object> rankCard(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rank", rs.getLong("rnk"));
        m.put("id", String.valueOf(rs.getLong("id")));
        m.put("name", rs.getString("name"));
        m.put("sign", rs.getString("sign"));
        m.put("emblem", rs.getString("emblem"));
        m.put("color", rs.getString("color"));
        m.put("logoUrl", MediaUrls.link(rs.getString("logo_url")));
        m.put("level", rs.getInt("level"));
        m.put("score", rs.getLong("score"));
        m.put("members", rs.getLong("members"));
        return m;
    }
}
