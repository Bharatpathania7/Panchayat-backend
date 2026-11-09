package com.panchayat.backend.security;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

/** Only authentication endpoints are limited; everything else never gets a 429 from the filter. */
class RateLimitFilterTests {

    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final RateLimitFilter filter = new RateLimitFilter(new RateLimiter(), metrics);

    private MockHttpServletResponse call(String method, String path) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, path);
        req.setRemoteAddr("203.0.113.7");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        return res;
    }

    @ParameterizedTest(name = "{0} is limited after {1} tries")
    @CsvSource({
            "/api/v1/auth/send-otp, 30",
            "/api/v1/auth/verify-otp, 30",
            "/api/v1/auth/google, 30",
            "/api/v1/auth/refresh, 120",
            "/api/admin/auth/login, 5",
    })
    void authEndpointsAreLimitedAndSayWhenToComeBack(String path, int limit) throws Exception {
        for (int i = 0; i < limit; i++) assertEquals(200, call("POST", path).getStatus(), "try " + (i + 1));
        MockHttpServletResponse over = call("POST", path);
        assertEquals(429, over.getStatus());
        assertTrue(Long.parseLong(over.getHeader("Retry-After")) > 0);
        assertEquals(1.0, metrics.get("panchayat.auth.rate_limited").counter().count());
    }

    @ParameterizedTest
    @CsvSource({
            "POST, /api/v1/voice-rooms/1/join",
            "POST, /api/v1/voice-rooms/1/leave",
            "GET, /api/v1/voice-rooms/1/participants",
            "GET, /api/v1/voice-rooms/live-counts",
            "POST, /api/v1/voice-rooms/1/seats/2/take",
            "POST, /api/v1/voice-metrics",
            "GET, /api/v1/wallet/me",
            "GET, /api/v1/users/me",
            "POST, /api/v1/auth/complete-profile",
            "POST, /api/v1/auth/logout",
    })
    void normalApisAreNeverLimited(String method, String path) throws Exception {
        for (int i = 0; i < 2_000; i++) assertEquals(200, call(method, path).getStatus());
    }

    @Test
    void onePersonsLimitDoesntTouchAnotherIp() throws Exception {
        for (int i = 0; i < 6; i++) call("POST", "/api/admin/auth/login");
        MockHttpServletRequest other = new MockHttpServletRequest("POST", "/api/admin/auth/login");
        other.setRemoteAddr("203.0.113.8");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(other, res, new MockFilterChain());
        assertEquals(200, res.getStatus());
    }
}
