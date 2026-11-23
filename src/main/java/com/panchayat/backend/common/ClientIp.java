package com.panchayat.backend.common;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The visitor's real IP, now that all traffic is proxied through Cloudflare.
 *
 * Cloudflare sets CF-Connecting-IP to the real visitor's address on every request it forwards, however many
 * hops are in between — nginx's own X-Forwarded-For chain (which Tomcat's RemoteIpValve walks) ends up
 * pointing at Cloudflare's shared edge IP instead, since that address is public and isn't in Tomcat's
 * trusted-proxy pattern. Using Cloudflare's own header avoids having to keep nginx/Tomcat in sync with
 * Cloudflare's IP ranges, which do change.
 *
 * CF-Connecting-IP is only trusted here because nginx's firewall is locked to Cloudflare's own IP ranges
 * (so nobody else can reach this server directly and forge the header); request.getRemoteAddr() is the
 * fallback for local/direct testing where that header is never sent.
 */
public final class ClientIp {

    private ClientIp() {
    }

    public static String of(HttpServletRequest req) {
        String cf = req.getHeader("CF-Connecting-IP");
        if (cf != null && !cf.isBlank()) return cf.strip();
        return req.getRemoteAddr();
    }
}
