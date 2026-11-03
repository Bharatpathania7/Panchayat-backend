package com.panchayat.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.panchayat.backend.admin.media.MediaUrls;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Request limits for the whole API, checked before anything else runs.
 *
 * Each request is counted against:
 *  - a global limit per IP address (stops floods, even with no token),
 *  - a global limit per signed-in account,
 *  - the first matching rule below (logins, OTPs, payments, uploads, chat,
 *    gifts, search…), per IP for sign-in endpoints and per account elsewhere.
 * Too many: 429 with Retry-After and a short message the apps show as is.
 *
 * Also refuses request bodies over 1 MB except file uploads (multipart), and
 * links to protected media (/uploads/media/…) without a valid, unexpired
 * signature (see MediaUrls).
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final long MIN = 60_000L;
    private static final long MAX_JSON_BODY = 1024L * 1024;

    /** method: null = any. scope: IP or USER (falls back to IP when not signed in). */
    private record Rule(String name, String method, Pattern path, int limit, long windowMs, boolean perIp) {
    }

    private static Rule ip(String name, String method, String regex, int limit, long windowMs) {
        return new Rule(name, method, Pattern.compile(regex), limit, windowMs, true);
    }

    private static Rule user(String name, String method, String regex, int limit, long windowMs) {
        return new Rule(name, method, Pattern.compile(regex), limit, windowMs, false);
    }

    private static final List<Rule> RULES = List.of(
            // Sign-in: brute force and SMS/email bombing
            ip("otp-send", "POST", "^/api/v1/auth/send-otp$", 5, 10 * MIN),
            ip("otp-verify", "POST", "^/api/v1/auth/verify-otp$", 10, 10 * MIN),
            ip("google", "POST", "^/api/v1/auth/google$", 20, MIN),
            ip("refresh", "POST", "^/api/v1/auth/refresh$", 30, MIN),
            ip("admin-login", "POST", "^/api/admin/auth/login$", 5, 15 * MIN),
            // Money
            ip("payu-callback", null, "^/api/v1/payments/callback.*", 60, MIN),
            user("payments", "POST", "^/api/v1/payments/.*", 10, MIN),
            user("room-password", "POST", "^/api/v1/voice-rooms/\\d+/password-check$", 10, 10 * MIN),
            // Things that cost storage or other people's attention
            user("chat-groups", "POST", "^/api/v1/chat/groups$", 10, 60 * MIN),
            user("chat-send", "POST", "^/api/v1/chat/conversations/\\d+/messages(/image)?$", 60, MIN),
            user("chat-blocks", null, "^/api/v1/chat/blocks/\\d+$", 30, MIN),
            user("room-create", "POST", "^/api/v1/voice-rooms$", 10, 60 * MIN),
            user("room-join", "POST", "^/api/v1/voice-rooms/\\d+/join$", 30, MIN),
            user("gifts", "POST", "^/api/v1/voice-rooms/\\d+/gifts$", 120, MIN),
            user("room-moderation", null, "^/api/v1/voice-rooms/\\d+/(kick|bans.*|admins/\\d+)$", 30, MIN),
            user("search", "GET", "^/api/v1/(search|presence)$", 60, MIN),
            user("rankings", "GET", "^/api/v1/rankings$", 60, MIN),
            user("profile-media", "POST", "^/api/v1/users/me/(avatar|banner)$", 10, 10 * MIN),
            user("admin", null, "^/api/(v1/)?admin/.*", 300, MIN)
    );

    private static final int IP_PER_MINUTE = 1200;
    private static final int USER_PER_MINUTE = 600;
    private static final int UPLOADS_PER_MINUTE = 30;

    private final RateLimiter limiter;

    public RateLimitFilter(RateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String path = req.getRequestURI();
        String method = req.getMethod();
        if ("OPTIONS".equals(method)) {
            chain.doFilter(req, res);
            return;
        }

        // Protected media: a valid, unexpired signature or nothing
        if (path.startsWith("/uploads/media/")) {
            if (!MediaUrls.valid(path, req.getParameter("md5"), req.getParameter("expires"))) {
                res.setStatus(HttpServletResponse.SC_FORBIDDEN);
                res.setContentType("application/json");
                res.getWriter().write("{\"message\":\"This link has expired.\"}");
                return;
            }
        }

        String ipAddr = req.getRemoteAddr();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String who = auth != null && auth.isAuthenticated() && auth.getName() != null && !"anonymousUser".equals(auth.getName())
                ? auth.getName().toLowerCase(Locale.ROOT) : null;

        // Request bodies: 1 MB, except file uploads (those have their own limits)
        String type = req.getContentType();
        boolean multipart = type != null && type.toLowerCase(Locale.ROOT).startsWith("multipart/");
        if (!multipart && req.getContentLengthLong() > MAX_JSON_BODY) {
            reject(res, 413, 0, "That request is too large.");
            return;
        }

        long wait = limiter.hit("ip:" + ipAddr, IP_PER_MINUTE, MIN);
        if (wait == 0 && who != null) wait = limiter.hit("user:" + who, USER_PER_MINUTE, MIN);
        if (wait == 0 && multipart) wait = limiter.hit("upload:" + (who != null ? who : ipAddr), UPLOADS_PER_MINUTE, MIN);
        if (wait == 0) {
            for (Rule r : RULES) {
                if ((r.method() == null || r.method().equals(method)) && r.path().matcher(path).matches()) {
                    String key = r.name() + ":" + (r.perIp() || who == null ? "ip:" + ipAddr : who);
                    wait = limiter.hit(key, r.limit(), r.windowMs());
                    break;
                }
            }
        }
        if (wait > 0) {
            log.info("Rate limited {} {} for {}", method, path, who != null ? "a signed-in user" : ipAddr);
            reject(res, 429, wait, wait >= 120
                    ? "Too many attempts. Try again in " + ((wait + 59) / 60) + " minutes."
                    : "Too many requests. Try again in " + wait + " seconds.");
            return;
        }
        chain.doFilter(req, res);
    }

    private static void reject(HttpServletResponse res, int status, long retryAfter, String message) throws IOException {
        res.setStatus(status);
        if (retryAfter > 0) res.setHeader("Retry-After", String.valueOf(retryAfter));
        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write("{\"message\":\"" + message.replace("\"", "'") + "\"}");
    }
}
