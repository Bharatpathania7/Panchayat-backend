package com.panchayat.backend.admin.audit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The admin audit log: every change made through the admin API (any method
 * but GET), with who made it, their role, the path, the result and their IP.
 * Sign-in attempts are logged too (without the username typed or password).
 */
@Configuration
public class AdminAuditConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AdminAuditConfig.class);

    private final JdbcTemplate jdbc;

    public AdminAuditConfig(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public void afterCompletion(HttpServletRequest req, HttpServletResponse res, Object handler, Exception ex) {
                String method = req.getMethod();
                if ("GET".equals(method) || "OPTIONS".equals(method) || "HEAD".equals(method)) return;
                try {
                    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                    String user = null;
                    String role = null;
                    if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getName())) {
                        user = cut(auth.getName(), 60);
                        role = auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                                .filter(a -> a.startsWith("ROLE_")).map(a -> a.substring(5)).findFirst().orElse(null);
                    }
                    int status = ex != null && res.getStatus() < 400 ? 500 : res.getStatus();
                    jdbc.update("insert into admin_audit_logs (admin_username, admin_role, method, path, status, ip) values (?, ?, ?, ?, ?, ?)",
                            user, cut(role, 20), method, cut(req.getRequestURI(), 300), status, cut(ip(req), 64));
                } catch (Exception e) {
                    log.warn("Couldn't write the admin audit log: {}", e.getMessage());
                }
            }
        }).addPathPatterns("/api/v1/admin/**", "/api/admin/**");
    }

    private static String ip(HttpServletRequest req) {
        String fwd = req.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) return fwd.split(",")[0].trim();
        return req.getRemoteAddr();
    }

    private static String cut(String s, int max) {
        return s == null ? null : (s.length() > max ? s.substring(0, max) : s);
    }
}
