package com.panchayat.backend.auth.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * One account per phone. The app sends a device id (X-Device-Id: the phone's own id inside the Android app, a random id
 * kept by the browser on the web) when someone signs in or opens an account. The account remembers the phone it was
 * opened on (users.signup_device, stored as a hash). Opening a second account on that phone is refused until the first
 * one is deleted (or an admin frees the phone). Signing in to an account that already exists is never blocked.
 *
 * A request without a device id (an old app version) is not blocked, because the phone can't be told apart.
 */
@Component
public class DeviceGuard {

    public static final String HEADER = "X-Device-Id";
    public static final String MESSAGE = "You already have an account on this phone. Delete that account first (Profile, then Delete account), then you can open a new one.";

    private final JdbcTemplate jdbc;

    public DeviceGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
        if (h == null || !enabled()) return;
        Integer n = jdbc.queryForObject("select count(*) from users where signup_device = ?", Integer.class, h);
        if (n != null && n > 0) throw new AuthService.AuthException(MESSAGE);
    }

    /** Ties the account to this phone if it isn't tied to one yet (a new account, or an older one signing in). */
    public void remember(Long userId) {
        String h = currentHash();
        if (h == null || userId == null) return;
        jdbc.update("update users set signup_device = ? where id = ? and signup_device is null", h, userId);
    }
}
