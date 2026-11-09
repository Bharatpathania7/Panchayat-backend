
        package com.panchayat.backend.auth.SecurityConfig;

import com.panchayat.backend.admin.auth.entity.AdminAccount;
import com.panchayat.backend.admin.users.AccountBanService;
import com.panchayat.backend.admin.auth.repository.AdminAccountRepository;
import com.panchayat.backend.auth.entity.Role;
import com.panchayat.backend.auth.entity.User;
import com.panchayat.backend.auth.repository.UserRepository;

import io.jsonwebtoken.JwtException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log =
            LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final AdminAccountRepository adminAccountRepository;
    private final AccountBanService bans;

    private static final Set<String> ADMIN_ROLES = Set.of(
            "ADMIN",
            "SUPER_ADMIN",
            "MODERATOR"
    );

    public JwtAuthenticationFilter(
            JwtService jwtService,
            UserRepository userRepository,
            AdminAccountRepository adminAccountRepository,
            AccountBanService bans
    ) {
        this.bans = bans;
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.adminAccountRepository = adminAccountRepository;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");
        String requestUri = request.getRequestURI();

        // No bearer token: continue and let Spring Security decide.
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.debug(
                    "No Bearer token provided. URI={}",
                    requestUri
            );

            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7).trim();

        try {
            if (!jwtService.isValid(token)) {
                log.warn(
                        "JWT validation failed. URI={}",
                        requestUri
                );

                SecurityContextHolder.clearContext();
                filterChain.doFilter(request, response);
                return;
            }

            Long accountId = jwtService.extractUserId(token);
            String accountType = jwtService.extractAccountType(token);

            if (accountType == null) {
                log.warn(
                        "JWT account type is missing. URI={}",
                        requestUri
                );

                SecurityContextHolder.clearContext();
                filterChain.doFilter(request, response);
                return;
            }

            if (SecurityContextHolder.getContext().getAuthentication() == null) {

                if ("USER".equals(accountType)) {

                    // A banned or suspended account is refused outright, with the reason
                    AccountBanService.Ban ban = bans.activeBan(accountId);
                    if (ban != null) {
                        SecurityContextHolder.clearContext();
                        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                        response.setContentType("application/json;charset=UTF-8");
                        response.getWriter().write("{\"status\":403,\"error\":\"ACCOUNT_SUSPENDED\",\"permanent\":" + ban.permanent()
                                + ",\"until\":" + (ban.until() == null ? "null" : "\"" + ban.until() + "\"")
                                + ",\"message\":\"" + ban.message().replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");
                        return;
                    }

                    authenticateUser(request, accountId);

                } else if (ADMIN_ROLES.contains(accountType)) {

                    authenticateAdmin(
                            request,
                            accountId,
                            accountType
                    );

                } else {

                    log.warn(
                            "Unsupported JWT account type. URI={}",
                            requestUri
                    );
                }
            }

            if (SecurityContextHolder.getContext().getAuthentication() != null) {
                log.debug(
                        "JWT authentication successful. URI={}, accountType={}",
                        requestUri,
                        accountType
                );
            } else {
                log.warn(
                        "JWT was valid, but authentication was not established. URI={}",
                        requestUri
                );
            }

        } catch (JwtException | IllegalArgumentException ex) {

            log.warn(
                    "JWT processing failed. URI={}, exceptionType={}",
                    requestUri,
                    ex.getClass().getSimpleName()
            );

            SecurityContextHolder.clearContext();
        }

        filterChain.doFilter(request, response);
    }

    private void authenticateUser(
            HttpServletRequest request,
            Long userId
    ) {
        Optional<User> optionalUser = userRepository.findById(userId);

        if (optionalUser.isEmpty()) {
            log.warn("JWT user lookup failed: user not found.");
            return;
        }

        User user = optionalUser.get();
        Role role = user.getRole();

        if (role == null) {
            log.warn("JWT user lookup succeeded, but user role is missing.");
            return;
        }

        setAuthentication(
                request,
                user.getEmail(),
                role.name()
        );

        log.debug("User authentication established.");
    }

    private void authenticateAdmin(
            HttpServletRequest request,
            Long adminId,
            String tokenRole
    ) {
        Optional<AdminAccount> optionalAdmin =
                adminAccountRepository.findById(adminId);

        if (optionalAdmin.isEmpty()) {
            log.warn("JWT admin lookup failed: admin not found.");
            return;
        }

        AdminAccount admin = optionalAdmin.get();

        if (!admin.isEnabled() || admin.getRole() == null) {
            log.warn(
                    "JWT admin authentication rejected: account disabled or role missing."
            );
            return;
        }

        String databaseRole = admin.getRole()
                .trim()
                .toUpperCase(Locale.ROOT);

        // Reject unsupported or mismatched roles.
        // This also invalidates the old token after a role change.
        if (!ADMIN_ROLES.contains(databaseRole)
                || !databaseRole.equals(tokenRole)) {

            log.warn(
                    "JWT admin authentication rejected: role mismatch or unsupported role."
            );
            return;
        }

        setAuthentication(
                request,
                admin.getUsername(),
                databaseRole
        );

        log.debug("Admin authentication established.");
    }

    private void setAuthentication(
            HttpServletRequest request,
            String principal,
            String role
    ) {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        List.of(
                                new SimpleGrantedAuthority("ROLE_" + role)
                        )
                );

        authentication.setDetails(
                new WebAuthenticationDetailsSource()
                        .buildDetails(request)
        );

        SecurityContextHolder.getContext()
                .setAuthentication(authentication);
    }
}
