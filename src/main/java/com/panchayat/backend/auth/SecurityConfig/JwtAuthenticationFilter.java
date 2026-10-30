
        package com.panchayat.backend.auth.SecurityConfig;

import com.panchayat.backend.admin.auth.entity.AdminAccount;
import com.panchayat.backend.admin.auth.repository.AdminAccountRepository;
import com.panchayat.backend.auth.entity.Role;
import com.panchayat.backend.auth.entity.User;
import com.panchayat.backend.auth.repository.UserRepository;

import io.jsonwebtoken.JwtException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

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

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final AdminAccountRepository adminAccountRepository;

    private static final Set<String> ADMIN_ROLES = Set.of(
            "ADMIN",
            "SUPER_ADMIN",
            "MODERATOR"
    );

    public JwtAuthenticationFilter(
            JwtService jwtService,
            UserRepository userRepository,
            AdminAccountRepository adminAccountRepository
    ) {
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

        // No bearer token: continue and let Spring Security decide.
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7).trim();

        try {
            if (!jwtService.isValid(token)) {
                SecurityContextHolder.clearContext();
                filterChain.doFilter(request, response);
                return;
            }

            Long accountId = jwtService.extractUserId(token);
            String accountType = jwtService.extractAccountType(token);

            if (SecurityContextHolder.getContext().getAuthentication() == null) {

                if ("USER".equals(accountType)) {
                    authenticateUser(request, accountId);

                } else if (ADMIN_ROLES.contains(accountType)) {
                    authenticateAdmin(request, accountId, accountType);
                }
            }

        } catch (JwtException | IllegalArgumentException ex) {
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
            return;
        }

        User user = optionalUser.get();
        Role role = user.getRole();

        if (role == null) {
            return;
        }

        setAuthentication(
                request,
                user.getEmail(),
                role.name()
        );
    }

    private void authenticateAdmin(
            HttpServletRequest request,
            Long adminId,
            String tokenRole
    ) {
        Optional<AdminAccount> optionalAdmin =
                adminAccountRepository.findById(adminId);

        if (optionalAdmin.isEmpty()) {
            return;
        }

        AdminAccount admin = optionalAdmin.get();

        if (!admin.isEnabled() || admin.getRole() == null) {
            return;
        }

        String databaseRole = admin.getRole()
                .trim()
                .toUpperCase(Locale.ROOT);

        // Reject unsupported or mismatched roles.
        // This also invalidates the old token after a role change.
        if (!ADMIN_ROLES.contains(databaseRole)
                || !databaseRole.equals(tokenRole)) {
            return;
        }

        setAuthentication(
                request,
                admin.getUsername(),
                databaseRole
        );
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
