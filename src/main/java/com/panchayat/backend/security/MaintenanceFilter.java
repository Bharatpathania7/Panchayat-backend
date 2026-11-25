package com.panchayat.backend.security;

import com.panchayat.backend.admin.settings.AppSettingsService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Maintenance mode (Settings page, admin panel): while on, every request gets 503 with the
 * configured message, except the admin panel itself (staff keep access to turn it back off) and
 * the health checks the deploy watchdog depends on (so a deploy's own maintenance window never
 * looks like a crash and triggers an unwanted restart).
 */
@Component
public class MaintenanceFilter extends OncePerRequestFilter {

    private final AppSettingsService settings;

    public MaintenanceFilter(AppSettingsService settings) {
        this.settings = settings;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) return true;
        String path = request.getRequestURI();
        // Admin login / refresh live under /api/admin/auth: staff must be able to sign in to turn maintenance off
        return path.startsWith("/api/v1/admin/") || path.startsWith("/api/admin/") || path.equals("/api/v1/health") || path.equals("/api/v1/gifts")
                // the public Products & Pricing page works during maintenance too
                || path.equals("/api/v1/wallet/coin-packs") || path.equals("/api/v1/vip/plans") || path.equals("/api/v1/manual-topup/royal")
                || path.equals("/api/v1/status") || path.equals("/error");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        if (!settings.isMaintenance()) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"message\":\"" + settings.maintenanceMessage().replace("\"", "'") + "\"}");
    }
}
