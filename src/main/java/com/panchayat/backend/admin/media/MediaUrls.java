package com.panchayat.backend.admin.media;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Links to media stored on this server (gift pictures and animations, reward
 * effects): signed and expiring, so a copied link stops working.
 *
 * The database keeps the path ("/uploads/media/…"). Every answer turns it into
 *   {MEDIA_PUBLIC_URL}/uploads/media/…?md5=<signature>&expires=<unix seconds>
 * The signature is nginx's secure_link scheme, base64url(md5(expires + path + " " + secret)),
 * so nginx can serve the files straight from disk and check the same signature
 * (deploy/nginx). The backend checks it too (RateLimitFilter) when it serves them.
 *
 * Links last for app.media.link-hours (24 by default) and up to twice that:
 * they are cut on fixed boundaries, so everyone gets the same link for a while
 * and phones can cache the file instead of downloading it again.
 */
@Component
public class MediaUrls {

    private static final String PREFIX = "/uploads/media/";
    private static volatile String base = "";
    private static volatile byte[] secret = new byte[0];
    private static volatile long periodSeconds = 24 * 3600L;

    public MediaUrls(@Value("${app.media.public-url:}") String publicUrl,
                     @Value("${app.media.link-secret:}") String linkSecret,
                     @Value("${app.jwt.secret:}") String jwtSecret,
                     @Value("${app.media.link-hours:24}") long hours) {
        String b = publicUrl == null ? "" : publicUrl.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        base = b;
        // Its own secret when set (nginx needs the same one); otherwise one derived from the JWT secret
        String s = linkSecret != null && !linkSecret.isBlank() ? linkSecret.trim() : hex(sha256("media-links:" + jwtSecret));
        secret = s.getBytes(StandardCharsets.UTF_8);
        periodSeconds = Math.max(1, hours) * 3600L;
    }

    /** A full, signed link for a stored path; full links (older Cloudinary ones) pass through. */
    public static String link(String stored) {
        if (stored == null || stored.isBlank()) return null;
        if (stored.startsWith("http://") || stored.startsWith("https://")) return stored;
        String path = stored;
        if (path.startsWith(PREFIX)) {
            long now = System.currentTimeMillis() / 1000;
            long expires = (now / periodSeconds + 2) * periodSeconds;
            path = path + "?md5=" + sign(stored, expires) + "&expires=" + expires;
        }
        return base.isEmpty() ? path : base + path;
    }

    /** Whether a request for path carries a valid, unexpired signature. */
    public static boolean valid(String path, String md5, String expires) {
        if (path == null || md5 == null || expires == null) return false;
        long exp;
        try {
            exp = Long.parseLong(expires);
        } catch (NumberFormatException e) {
            return false;
        }
        if (exp < System.currentTimeMillis() / 1000) return false;
        return MessageDigest.isEqual(sign(path, exp).getBytes(StandardCharsets.UTF_8), md5.getBytes(StandardCharsets.UTF_8));
    }

    private static String sign(String path, long expires) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update((expires + path + " ").getBytes(StandardCharsets.UTF_8));
            md.update(secret);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(md.digest());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
