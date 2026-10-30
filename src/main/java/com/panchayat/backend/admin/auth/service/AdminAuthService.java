
        package com.panchayat.backend.admin.auth.service;

import com.panchayat.backend.admin.auth.dto.AdminLoginRequest;
import com.panchayat.backend.admin.auth.dto.AdminLoginResponse;
import com.panchayat.backend.admin.auth.entity.AdminAccount;
import com.panchayat.backend.admin.auth.repository.AdminAccountRepository;
import com.panchayat.backend.auth.SecurityConfig.JwtService;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Set;

@Service
public class AdminAuthService {

    private static final Set<String> ALLOWED_ROLES = Set.of(
            "SUPER_ADMIN",
            "ADMIN",
            "MODERATOR"
    );

    private final AdminAccountRepository adminAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AdminAuthService(
            AdminAccountRepository adminAccountRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService
    ) {
        this.adminAccountRepository = adminAccountRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public AdminLoginResponse login(AdminLoginRequest request) {

        // 1. Validate request
        if (request == null
                || request.username() == null
                || request.username().isBlank()
                || request.password() == null
                || request.password().isBlank()) {

            throw new BadCredentialsException(
                    "Username and password are required"
            );
        }

        // 2. Find admin account
        String username = request.username().trim();

        AdminAccount admin = adminAccountRepository
                .findByUsernameIgnoreCase(username)
                .orElseThrow(() ->
                        new BadCredentialsException(
                                "Invalid username or password"
                        )
                );

        // 3. Check account status
        if (!admin.isEnabled()) {
            throw new AccessDeniedException(
                    "This admin account is disabled"
            );
        }

        // 4. Verify password
        if (admin.getPasswordHash() == null
                || admin.getPasswordHash().isBlank()
                || !passwordEncoder.matches(
                request.password(),
                admin.getPasswordHash()
        )) {

            throw new BadCredentialsException(
                    "Invalid username or password"
            );
        }

        // 5. Validate and normalize role
        String role = admin.getRole() == null
                ? ""
                : admin.getRole().trim().toUpperCase(Locale.ROOT);

        if (!ALLOWED_ROLES.contains(role)) {
            throw new AccessDeniedException(
                    "Admin access denied"
            );
        }

        // 6. Generate JWT using the actual database role
        String token = jwtService.generateToken(
                admin.getId(),
                admin.getUsername(),
                role
        );

        // 7. Return login response
        return new AdminLoginResponse(
                true,
                "Admin login successful",
                "Bearer",
                token,
                new AdminLoginResponse.AdminUser(
                        admin.getId(),
                        admin.getUsername(),
                        role
                )
        );
    }
}
