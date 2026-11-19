package com.panchayat.backend.auth.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/**
 * One account per phone. The app sends a device id (X-Device-Id: the phone's own id inside the Android app, a random id
 * kept by the browser on the web) when someone signs in or opens an account. The account remembers the phone it was
 * opened on (users.signup_device, stored as a hash). Opening a second account on that phone is refused until the first
 * one is deleted (or an admin frees the phone). Signing in to an account that already exists is never blocked.
 *
 * In a browser the id can be lost (a private window, cleared data), so the browser also sends a fingerprint (X-Device-Fp,
 * a hash of its screen, graphics chip and so on). Many phones of one model look alike, so a fingerprint only counts
 * together with the network: the same fingerprint from the same connection as an existing account is the same phone.
 *
 * A request without a device id (an old app version) is not blocked, because the phone can't be told apart.
 */
@Component
public class DeviceGuard {

    public static final String HEADER = "X-Device-Id";
    public static final String FP_HEADER = "X-Device-Fp";
    public static final String MESSAGE = "You already have an account on this phone. Delete that account first (Profile, then Delete account), then you can open a new one.";

    private final JdbcTemplate jdbc;
    private final com.panchayat.backend.rewards.NetworkGuard network;

    public DeviceGuard(JdbcTemplate jdbc, com.panchayat.backend.rewards.NetworkGuard network) {
        this.jdbc = jdbc;
        this.network = network;
    }

    public boolean enabled() {
        Boolean on = jdbc.query("select one_account_per_device from referral_settings where id = 1", rs -> rs.next() ? rs.getBoolean(1) : null);
        return on == null || on;
    }

    /** The hashed id of the phone making this request, or null when none was sent. */
    public String currentHash() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) return null;
        HttpServletRequest req = attrs.getRequest();
        return hashOf(req.getHeader(HEADER));
    }

    /** The browser fingerprint of this request (already a SHA-256 hex from the website), or null. */
    public String currentFingerprint() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) return null;
        String fp = attrs.getRequest().getHeader(FP_HEADER);
        if (fp == null) return null;
        String f = fp.strip().toLowerCase();
        return f.matches("[0-9a-f]{64}") ? f : null;
    }

    static String hashOf(String raw) {
        if (raw == null) return null;
        String id = raw.strip();
        if (!id.matches("[A-Za-z0-9._-]{16,100}")) return null;
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(("panchayat-device:" + id).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (Exception e) {
            return null;
        }
    }

    /** Refuses opening a new account on a phone that already has one. */
    public void assertCanOpenAccount() {
        String h = currentHash();
        String fp = currentFingerprint();
        String net = network.currentNetwork();
        if ((h == null && fp == null) || !enabled()) return;
        // The same phone id, or the same fingerprint from the same connection (a private window or cleared data)
        Integer n = jdbc.queryForObject("select count(*) from users where (?::text is not null and signup_device = ?)"
                + " or (?::text is not null and ?::text is not null and signup_fp = ? and signup_ip = ?)", Integer.class, h, h, fp, net, fp, net);
        if (n != null && n > 0) throw new AuthService.AuthException(MESSAGE);
    }

    public static final String BANNED_MESSAGE = "This phone has been banned from Panchayat.";

    /**
     * Refuses a phone an admin banned for good: it can't sign in, and can't open an account. The phone id always counts.
     * The browser fingerprint together with the network only counts for a NEW account (many phones of one model look
     * alike, so it must never lock an existing person out).
     */
    public void assertNotBanned(boolean newAccount) {
        String h = currentHash();
        String fp = newAccount ? currentFingerprint() : null;
        String net = newAccount ? network.currentNetwork() : null;
        if (h == null && fp == null) return;
        Integer n = jdbc.queryForObject("select count(*) from banned_devices where (?::text is not null and device_hash = ?)"
                + " or (?::text is not null and ?::text is not null and fp = ? and ip = ?)", Integer.class, h, h, fp, net, fp, net);
        if (n != null && n > 0) throw new AuthService.AuthException(BANNED_MESSAGE);
    }

    /** Bans every phone this account has been seen on, for good. Returns how many phones were put on the list. */
    public int banDevicesOf(Long userId, String reason, String admin) {
        String why = reason == null || reason.isBlank() ? null : reason.strip().substring(0, Math.min(300, reason.strip().length()));
        List<String> hashes = jdbc.queryForList("select device_hash from user_devices where user_id = ?"
                + " union select signup_device from users where id = ? and signup_device is not null", String.class, userId, userId);
        int count = 0;
        for (String h : hashes) {
            count += jdbc.update("insert into banned_devices (device_hash, user_id, reason, banned_by) values (?, ?, ?, ?)"
                    + " on conflict (device_hash) where device_hash is not null do nothing", h, userId, why, admin);
        }
        // The browser fingerprint + network of the signup, for a person who clears their data to start again
        jdbc.query("select signup_fp, signup_ip from users where id = ?", rs -> {
            String fp = rs.getString(1);
            String ip = rs.getString(2);
            if (fp != null && ip != null && jdbc.queryForObject("select count(*) from banned_devices where fp = ? and ip = ?", Integer.class, fp, ip) == 0) {
                jdbc.update("insert into banned_devices (fp, ip, user_id, reason, banned_by) values (?, ?, ?, ?, ?)", fp, ip, userId, why, admin);
            }
        }, userId);
        return count;
    }

    /** Lifts the phone bans that came from this account (when the admin unbans it). */
    public int unbanDevicesOf(Long userId) {
        return jdbc.update("delete from banned_devices where user_id = ?", userId);
    }

    /** Ties the account to this phone if it isn't tied to one yet (a new account, or an older one signing in). */
    public void remember(Long userId, boolean created) {
        if (userId == null) return;
        String h = currentHash();
        // Every phone the account is seen on, so a phone ban reaches all of them
        if (h != null) jdbc.update("insert into user_devices (user_id, device_hash) values (?, ?) on conflict (user_id, device_hash) do update set last_seen = now()", userId, h);
        if (h != null) jdbc.update("update users set signup_device = ? where id = ? and signup_device is null", h, userId);
        String fp = currentFingerprint();
        if (fp != null) jdbc.update("update users set signup_fp = ? where id = ? and signup_fp is null", fp, userId);
        // Only a new account records its network here (an older account signing in must not start counting against its Wi-Fi)
        String net = network.currentNetwork();
        if (created && net != null) jdbc.update("update users set signup_ip = ? where id = ? and signup_ip is null", net, userId);
    }
}
