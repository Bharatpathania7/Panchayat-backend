package com.panchayat.backend.rewards;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Refer & earn in the admin panel.
 *
 *   GET /api/v1/admin/manage/referrals              settings, totals and the top inviters
 *   PUT /api/v1/admin/manage/referrals/settings     { enabled, reward, welcomeGift }
 *   GET /api/v1/admin/manage/referrals/list?q=&page=&size=   every referral: who invited whom, when, what was paid
 */
@RestController
@RequestMapping("/api/v1/admin/manage/referrals")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
public class AdminReferralController {

    private static final long MAX_COINS = 100_000_000L;

    private final JdbcTemplate jdbc;
    private final WelcomeAndReferralController referrals;
    private final NetworkGuard network;
    private final com.panchayat.backend.auth.service.DeviceGuard devices;

    public AdminReferralController(JdbcTemplate jdbc, WelcomeAndReferralController referrals, NetworkGuard network, com.panchayat.backend.auth.service.DeviceGuard devices) {
        this.devices = devices;
        this.jdbc = jdbc;
        this.referrals = referrals;
        this.network = network;
    }

    public record SettingsBody(Boolean enabled, Long reward, Long welcomeGift, Integer maxWelcomePerNetwork, Boolean blockSameNetworkGifts, Boolean oneAccountPerDevice) {
    }

    private Map<String, Object> overview() {
        WelcomeAndReferralController.Rates r = referrals.rates();
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("enabled", r.enabled());
        settings.put("reward", r.reward());
        settings.put("welcomeGift", r.welcomeGift());
        NetworkGuard.Policy np = network.policy();
        settings.put("maxWelcomePerNetwork", np.maxWelcomePerNetwork());
        settings.put("blockSameNetworkGifts", np.blockSameNetworkGifts());
        settings.put("oneAccountPerDevice", devices.enabled());

        Map<String, Object> stats = jdbc.queryForObject(
                "select count(*) as total,"
                        + " count(*) filter (where referred_at >= date_trunc('day', now())) as today,"
                        + " count(*) filter (where referred_at >= now() - interval '7 days') as week,"
                        + " coalesce(sum(referral_reward), 0) as paid,"
                        + " count(distinct referred_by) as inviters"
                        + " from users where referred_by is not null",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("total", rs.getLong("total"));
                    m.put("today", rs.getLong("today"));
                    m.put("week", rs.getLong("week"));
                    m.put("coinsPaid", rs.getLong("paid"));
                    m.put("inviters", rs.getLong("inviters"));
                    return m;
                });

        List<Map<String, Object>> top = jdbc.query(
                "select i.id, i.public_id, i.full_name, count(*) as friends, coalesce(sum(u.referral_reward), 0) as earned"
                        + " from users u join users i on i.id = u.referred_by"
                        + " group by i.id, i.public_id, i.full_name order by friends desc, earned desc limit 10",
                (rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("userId", String.valueOf(rs.getLong("id")));
                    m.put("publicId", rs.getString("public_id"));
                    m.put("name", rs.getString("full_name"));
                    m.put("friends", rs.getLong("friends"));
                    m.put("earned", rs.getLong("earned"));
                    return m;
                });

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("settings", settings);
        out.put("stats", stats);
        out.put("top", top);
        return out;
    }

    @GetMapping
    public Map<String, Object> get() {
        return overview();
    }

    @PutMapping("/settings")
    public Map<String, Object> put(@RequestBody SettingsBody b) {
        if (b == null || b.enabled() == null || b.reward() == null || b.welcomeGift() == null) {
            throw bad("Send enabled, the inviter reward and the welcome gift.");
        }
        if (b.reward() < 0 || b.reward() > MAX_COINS || b.welcomeGift() < 0 || b.welcomeGift() > MAX_COINS) {
            throw bad("Coins must be 0 to 100,000,000.");
        }
        int per = b.maxWelcomePerNetwork() == null ? network.policy().maxWelcomePerNetwork() : b.maxWelcomePerNetwork();
        if (per < 0 || per > 1000) throw bad("Accounts per network must be 0 to 1000 (0 = no limit).");
        boolean block = b.blockSameNetworkGifts() == null ? network.policy().blockSameNetworkGifts() : b.blockSameNetworkGifts();
        boolean oneDevice = b.oneAccountPerDevice() == null ? devices.enabled() : b.oneAccountPerDevice();
        jdbc.update("update referral_settings set enabled = ?, inviter_reward = ?, welcome_gift = ?, max_welcome_per_network = ?, block_same_network_gifts = ?, one_account_per_device = ? where id = 1",
                b.enabled(), b.reward(), b.welcomeGift(), per, block, oneDevice);
        return overview();
    }

    public record ReleaseBody(String account) {
    }

    /** Lets the person open another account: forgets which phone this account was opened on. Takes the account ID (the number on their profile, not the internal id). */
    @PostMapping("/release-device")
    public Map<String, Object> release(@RequestBody ReleaseBody b) {
        String a = b == null || b.account() == null ? "" : b.account().strip();
        if (!a.matches("\\d{1,15}")) throw bad("Enter the account ID (the number on their profile).");
        long n = Long.parseLong(a);
        int changed = jdbc.update("update users set signup_device = null where public_id = ? and signup_device is not null", n);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("released", changed);
        return out;
    }

    @GetMapping("/list")
    public Map<String, Object> list(@RequestParam(required = false) String q,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "25") int size) {
        int s = Math.max(1, Math.min(100, size));
        int p = Math.max(0, page);
        String term = q == null ? "" : q.strip();
        List<Object> args = new ArrayList<>();
        String where = " where u.referred_by is not null";
        if (!term.isEmpty()) {
            if (term.matches("\\d{1,15}")) {
                where += " and (u.public_id = ? or i.public_id = ? or u.id = ? or i.id = ?)";
                long n = Long.parseLong(term);
                args.add(n);
                args.add(n);
                args.add(n);
                args.add(n);
            } else {
                where += " and (u.full_name ilike ? or i.full_name ilike ? or u.email ilike ? or i.email ilike ?)";
                String like = "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                args.add(like);
                args.add(like);
                args.add(like);
                args.add(like);
            }
        }
        String from = " from users u join users i on i.id = u.referred_by" + where;
        Long total = jdbc.queryForObject("select count(*)" + from, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add((long) p * s);
        List<Map<String, Object>> items = jdbc.query(
                "select u.id as uid, u.public_id as upub, u.full_name as uname, u.email as uemail, u.created_at as ucreated, u.referred_at, u.referral_reward, u.referral_note,"
                        + " i.id as iid, i.public_id as ipub, i.full_name as iname, i.email as iemail"
                        + from + " order by u.referred_at desc nulls last, u.id desc limit ? offset ?",
                (rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("friend", person(rs.getLong("uid"), rs.getString("upub"), rs.getString("uname"), rs.getString("uemail")));
                    m.put("inviter", person(rs.getLong("iid"), rs.getString("ipub"), rs.getString("iname"), rs.getString("iemail")));
                    m.put("joinedAt", rs.getTimestamp("referred_at") == null ? null : rs.getTimestamp("referred_at").toInstant().toString());
                    m.put("accountCreatedAt", rs.getTimestamp("ucreated") == null ? null : rs.getTimestamp("ucreated").toInstant().toString());
                    m.put("reward", rs.getLong("referral_reward"));
                    m.put("note", rs.getString("referral_note"));
                    return m;
                }, pageArgs.toArray());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total == null ? 0 : total);
        out.put("page", p);
        out.put("size", s);
        out.put("items", items);
        return out;
    }

    private static Map<String, Object> person(long id, String publicId, String name, String email) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", String.valueOf(id));
        m.put("publicId", publicId);
        m.put("name", name);
        m.put("email", email);
        return m;
    }

    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }
}
