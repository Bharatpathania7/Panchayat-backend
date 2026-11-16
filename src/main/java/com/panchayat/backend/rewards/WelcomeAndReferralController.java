package com.panchayat.backend.rewards;

import com.panchayat.backend.admin.media.MediaUrls;
import com.panchayat.backend.auth.repository.UserRepository;
import com.panchayat.backend.sarpanch.SarpanchService;
import com.panchayat.backend.wallet.service.CoinWalletService;
import com.panchayat.backend.wallet.service.WalletPushService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Welcome red packet and referrals.
 *   GET  /api/v1/welcome-gift          { available, claimed, amount }
 *   POST /api/v1/welcome-gift/claim    opens Sarpanch's red packet once → { amount, balance }
 *   GET  /api/v1/referrals/me          { code, link, reward, count, earned, friends: [...] }
 *   POST /api/v1/referrals/apply { code }  a new account (first 7 days) names who invited it;
 *        the inviter gets REFERRAL_REWARD coins once and a Sarpanch message.
 *   GET  /api/v1/voice-rooms/live-counts    { roomId: people in the room now } (Rooms list)
 */
@RestController
public class WelcomeAndReferralController {

    public static final String SITE = "https://panchayat.world";

    private final JdbcTemplate jdbc;
    private final UserRepository users;
    @Autowired private com.panchayat.backend.cache.ReadCache cache;

    @Autowired @Lazy private CoinWalletService wallet;
    @Autowired @Lazy private WalletPushService walletPush;
    @Autowired @Lazy private SarpanchService sarpanch;
    @Autowired @Lazy private com.panchayat.backend.rooms.service.RoomStateService roomState;

    public WelcomeAndReferralController(JdbcTemplate jdbc, UserRepository users) {
        this.jdbc = jdbc;
        this.users = users;
    }

    /** The rates the admin panel sets (Referrals page): what the inviter earns and the new user's welcome red packet. */
    public record Rates(boolean enabled, long reward, long welcomeGift) {
    }

    public Rates rates() {
        Rates r = jdbc.query("select enabled, inviter_reward, welcome_gift from referral_settings where id = 1",
                rs -> rs.next() ? new Rates(rs.getBoolean(1), rs.getLong(2), rs.getLong(3)) : null);
        return r != null ? r : new Rates(true, 20_000, 50_000);
    }

    static String coins(long n) {
        return java.text.NumberFormat.getInstance(new java.util.Locale("en", "IN")).format(n);
    }

    /* ---------------- welcome red packet ---------------- */

    @GetMapping("/api/v1/welcome-gift")
    public Map<String, Object> welcome(Authentication auth) {
        Long me = me(auth);
        Map<String, Object> u = jdbc.queryForMap("select welcome_gift_offered, welcome_gift_claimed_at from users where id = ?", me);
        boolean offered = Boolean.TRUE.equals(u.get("welcome_gift_offered"));
        boolean claimed = u.get("welcome_gift_claimed_at") != null;
        return Map.of("available", offered && !claimed, "claimed", claimed, "amount", rates().welcomeGift());
    }

    @PostMapping("/api/v1/welcome-gift/claim")
    @Transactional
    public Map<String, Object> claim(Authentication auth) {
        Long me = me(auth);
        int n = jdbc.update("update users set welcome_gift_claimed_at = now() where id = ? and welcome_gift_offered and welcome_gift_claimed_at is null", me);
        if (n == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "This red packet was already opened.");
        long gift = rates().welcomeGift();
        var tx = wallet.credit(me, gift, "WELCOME_GIFT", "welcome:" + me, "welcome:" + me, "Sarpanch's welcome red packet");
        afterCommit(() -> walletPush.pushBalance(me, "WELCOME_GIFT", gift));
        return Map.of("amount", gift, "balance", tx.getBalanceAfter());
    }

    /* ---------------- referrals ---------------- */

    @GetMapping("/api/v1/referrals/me")
    public Map<String, Object> mine(Authentication auth) {
        Long me = me(auth);
        Long code = jdbc.queryForObject("select public_id from users where id = ?", Long.class, me);
        List<Map<String, Object>> friends = new ArrayList<>();
        jdbc.query("select id, full_name, avatar_url, referred_at, referral_reward from users where referred_by = ? order by referred_at desc limit 100",
                rs -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("userId", String.valueOf(rs.getLong("id")));
                    m.put("name", rs.getString("full_name"));
                    m.put("avatarUrl", MediaUrls.link(rs.getString("avatar_url")));
                    m.put("reward", rs.getLong("referral_reward"));
                    m.put("joinedAt", rs.getTimestamp("referred_at") == null ? null : rs.getTimestamp("referred_at").toInstant().toString());
                    friends.add(m);
                }, me);
        Long count = jdbc.queryForObject("select count(*) from users where referred_by = ?", Long.class, me);
        Long earned = jdbc.queryForObject("select coalesce(sum(referral_reward), 0) from users where referred_by = ?", Long.class, me);
        Rates rates = rates();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", String.valueOf(code));
        out.put("link", SITE + "/?ref=" + code);
        out.put("enabled", rates.enabled());
        out.put("reward", rates.reward());
        out.put("welcomeGift", rates.welcomeGift());
        out.put("count", count);
        out.put("earned", earned == null ? 0 : earned);
        out.put("friends", friends);
        return out;
    }

    /** Light read for screens that only show the rates (the profile menu, the invite text). */
    @GetMapping("/api/v1/referrals/rates")
    public Map<String, Object> ratesNow(Authentication auth) {
        me(auth);
        Rates r = rates();
        return Map.of("enabled", r.enabled(), "reward", r.reward(), "welcomeGift", r.welcomeGift());
    }

    public record ApplyRequest(String code) {
    }

    @PostMapping("/api/v1/referrals/apply")
    @Transactional
    public Map<String, Object> apply(@RequestBody ApplyRequest body, Authentication auth) {
        Long me = me(auth);
        String code = body == null || body.code() == null ? "" : body.code().trim();
        if (!code.matches("\\d{4,12}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That invite link isn't valid.");
        Rates rates = rates();
        if (!rates.enabled()) return Map.of("applied", false, "message", "Invites are paused right now.");
        Map<String, Object> u = jdbc.queryForMap("select referred_by, created_at, full_name, profile_completed from users where id = ? for update", me);
        if (u.get("referred_by") != null) return Map.of("applied", false, "message", "You already joined with an invite.");
        java.sql.Timestamp created = (java.sql.Timestamp) u.get("created_at");
        if (created != null && created.toInstant().isBefore(java.time.Instant.now().minus(java.time.Duration.ofDays(7)))) {
            return Map.of("applied", false, "message", "Invite links work for new accounts only.");
        }
        Long inviter = jdbc.query("select id from users where public_id = ? and not coalesce(official, false)", rs -> rs.next() ? rs.getLong(1) : null, Long.parseLong(code));
        if (inviter == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That invite link isn't valid.");
        if (inviter.equals(me)) return Map.of("applied", false, "message", "You can't invite yourself.");
        long reward = rates.reward();
        jdbc.update("update users set referred_by = ?, referred_at = now(), referral_reward = ? where id = ? and referred_by is null", inviter, reward, me);
        if (reward > 0) wallet.credit(inviter, reward, "REFERRAL_REWARD", "referral:" + me, "referral:" + me, "Friend joined with your link");
        String name = u.get("full_name") == null ? "A friend" : (String) u.get("full_name");
        if (reward > 0) afterCommit(() -> walletPush.pushBalance(inviter, "REFERRAL_REWARD", reward));
        sarpanch.notifySafely(inviter, "REFERRAL_JOINED", Map.of("name", name, "reward", coins(reward), "gift", coins(rates.welcomeGift())), "referral:" + me);
        return Map.of("applied", true);
    }

    /* ---------------- live people in rooms ---------------- */

    @GetMapping("/api/v1/voice-rooms/live-counts")
    public ResponseEntity<String> liveCounts(Authentication auth) {
        me(auth);
        // Every phone on the rooms list asks every 15 s: one database answer per 5 s is shared by all of them
        return cache.respond("live-counts", "all", java.time.Duration.ofSeconds(5), () -> {
            Map<String, Long> out = new LinkedHashMap<>();
            jdbc.query("select p.room_id, count(*) from voice_room_participants p join voice_rooms v on v.id = p.room_id"
                            + " where p.status = 'JOINED' and v.status = 'ACTIVE' group by p.room_id",
                    rs -> {
                        out.put(String.valueOf(rs.getLong(1)), rs.getLong(2));
                    });
            return out;
        });
    }

    /** People on mic (on a seat) in each live room: { roomId: n }. */
    @GetMapping("/api/v1/voice-rooms/on-mic-counts")
    public Map<String, Integer> onMicCounts(Authentication auth) {
        me(auth);
        Map<String, Integer> out = new LinkedHashMap<>();
        roomState.seatedCounts().forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handle(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("message", e.getReason() == null ? "That didn't work" : e.getReason()));
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

    private Long me(Authentication auth) {
        if (auth == null || auth.getName() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in again.");
        return users.findByEmailIgnoreCase(auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Sign in with a user account.")).getId();
    }
}
