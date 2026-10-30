
        package com.panchayat.backend.admin.auth.service;

import com.panchayat.backend.admin.auth.entity.AdminAccount;
import com.panchayat.backend.admin.auth.repository.AdminAccountRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class AdminAccountSeeder {

    @Bean
    CommandLineRunner seedAdminAccounts(
            AdminAccountRepository repository,
            PasswordEncoder passwordEncoder,
            @Value("${app.bootstrap.super-admin-password:}")
            String superAdminPassword,
            @Value("${app.bootstrap.admin-password:}")
            String adminPassword,
            @Value("${app.bootstrap.moderator-password:}")
            String moderatorPassword
    ) {
        return args -> {
            createIfMissing(
                    repository,
                    passwordEncoder,
                    "superadmin",
                    superAdminPassword,
                    "SUPER_ADMIN"
            );

            createIfMissing(
                    repository,
                    passwordEncoder,
                    "admin",
                    adminPassword,
                    "ADMIN"
            );

            createIfMissing(
                    repository,
                    passwordEncoder,
                    "moderator",
                    moderatorPassword,
                    "MODERATOR"
            );
        };
    }

    private void createIfMissing(
            AdminAccountRepository repository,
            PasswordEncoder passwordEncoder,
            String username,
            String rawPassword,
            String role
    ) {
        if (repository.existsByUsernameIgnoreCase(username)) {
            return;
        }

        if (rawPassword == null || rawPassword.length() < 12) {
            throw new IllegalStateException(
                    "Configure a strong bootstrap password for " + role
            );
        }

        AdminAccount account = new AdminAccount(
                username,
                passwordEncoder.encode(rawPassword),
                role
        );

        repository.save(account);
    }
}
