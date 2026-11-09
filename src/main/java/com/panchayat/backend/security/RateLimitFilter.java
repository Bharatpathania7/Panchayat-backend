
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
 * Authentication endpoint rate limiting.
 *
 * Rate limits apply only to login/authentication endpoints:
 *  - OTP send and verify
 *  - Google login
 *  - Token refresh
 *  - Admin login
 *
 * Normal application APIs are not rate limited by this filter.
 *
 * Also refuses request bodies over 1 MB except file uploads (multipart), and
 * links to protected media (/uploads/media/...) without a valid, unexpired
 * signature (see MediaUrls).
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final long MIN = 60_000L;
    private static final long MAX_JSON_BODY = 1024L * 1024;

    private record Rule(
            String name,
            String method,
            Pattern path,
            int limit,
            long windowMs
    ) {}

    private static Rule ip(
            String name,
            String method,
            String regex,
            int limit,
            long windowMs
    ) {
        return new Rule(name, method, Pattern.compile(regex), limit, windowMs);
    }

    /**
     * Only authentication endpoints are rate limited.
     */
    private static final List<Rule> RULES = List.of(
            ip("otp-send", "POST", "^/api/v1/auth/send-otp$", 5, 10 * MIN),
            ip("otp-verify", "POST", "^/api/v1/auth/verify-otp$", 10, 10 * MIN),
            ip("google", "POST", "^/api/v1/auth/google$", 20, MIN),
            ip("refresh", "POST", "^/api/v1/auth/refresh$", 30, MIN),
            ip("admin-login", "POST", "^/api/admin/auth/login$", 5, 15 * MIN)
    );

    private final RateLimiter limiter;

    public RateLimitFilter(RateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest req,
            HttpServletResponse res,
            FilterChain chain
    ) throws ServletException, IOException {

        String path = req.getRequestURI();
        String method = req.getMethod();

        if ("OPTIONS".equals(method)) {
            chain.doFilter(req, res);
            return;
        }

        // Protected media: a valid, unexpired signature is required.
        if (path.startsWith("/uploads/media/")) {
            if (!MediaUrls.valid(
                    path,
                    req.getParameter("md5"),
                    req.getParameter("expires")
            )) {
                res.setStatus(HttpServletResponse.SC_FORBIDDEN);
                res.setContentType("application/json");
                res.getWriter().write(
                        "{\"message\":\"This link has expired.\"}"
                );
                return;
            }
        }

        Authentication auth =
                SecurityContextHolder.getContext().getAuthentication();

        String who = auth != null
                && auth.isAuthenticated()
                && auth.getName() != null
                && !"anonymousUser".equals(auth.getName())
                ? auth.getName().toLowerCase(Locale.ROOT)
                : null;

        // Keep the existing 1 MB JSON request-body protection.
        String type = req.getContentType();
        boolean multipart = type != null
                && type.toLowerCase(Locale.ROOT).startsWith("multipart/");

        if (!multipart && req.getContentLengthLong() > MAX_JSON_BODY) {
            reject(res, 413, 0, "That request is too large.");
            return;
        }

        // Only matching authentication endpoints consume rate-limit tokens.
        long wait = 0;
        String ipAddr = req.getRemoteAddr();

        for (Rule rule : RULES) {
            if ((rule.method() == null || rule.method().equals(method))
                    && rule.path().matcher(path).matches()) {

                String key = rule.name() + ":ip:" + ipAddr;

                wait = limiter.hit(
                        key,
                        rule.limit(),
                        rule.windowMs()
                );

                break;
            }
        }

        if (wait > 0) {
            log.info(
                    "Rate limited {} {} for {}",
                    method,
                    path,
                    who != null ? "a signed-in user" : ipAddr
            );

            reject(
                    res,
                    429,
                    wait,
                    wait >= 120
                            ? "Too many attempts. Try again in "
                                + ((wait + 59) / 60) + " minutes."
                            : "Too many attempts. Try again in "
                                + wait + " seconds."
            );
            return;
        }

        chain.doFilter(req, res);
    }

    private static void reject(
            HttpServletResponse res,
            int status,
            long retryAfter,
            String message
    ) throws IOException {

        res.setStatus(status);

        if (retryAfter > 0) {
            res.setHeader("Retry-After", String.valueOf(retryAfter));
        }

        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");

        res.getWriter().write(
                "{\"message\":\"" + message.replace("\"", "'") + "\"}"
        );
    }
}

