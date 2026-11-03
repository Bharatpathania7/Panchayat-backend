package com.panchayat.backend.admin.media;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Links to media stored on this server. The database keeps the path
 * ("/uploads/media/gifts/animations/ab12….svga"); answers turn it into a full
 * link when app.media.public-url (MEDIA_PUBLIC_URL) is set, e.g.
 * https://media.panchayat.world served straight by nginx on the VPS.
 * Unset, the path is sent as is and the apps load it through their own
 * /backend (app) or /uploads (admin panel) proxy.
 */
@Component
public class MediaUrls {

    private static volatile String base = "";

    public MediaUrls(@Value("${app.media.public-url:}") String publicUrl) {
        String b = publicUrl == null ? "" : publicUrl.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        base = b;
    }

    /** Full link for a stored path; full links (older Cloudinary ones) pass through. */
    public static String link(String stored) {
        if (stored == null || stored.isBlank()) return null;
        if (stored.startsWith("http://") || stored.startsWith("https://")) return stored;
        return base.isEmpty() ? stored : base + stored;
    }
}
