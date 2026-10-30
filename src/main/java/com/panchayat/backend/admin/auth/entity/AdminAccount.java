
        package com.panchayat.backend.admin.auth.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;

@Entity
@Table(name = "admin_accounts")
public class AdminAccount {

    private static final Set<String> ALLOWED_ROLES =
            Set.of("SUPER_ADMIN", "ADMIN", "MODERATOR");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String username;

    @Column(nullable = false)
    private String passwordHash;

    @Column(nullable = false, length = 20)
    private String role = "ADMIN";

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected AdminAccount() {
    }

    public AdminAccount(String username, String passwordHash, String role) {
        setUsername(username);
        setPasswordHash(passwordHash);
        setRole(role);
        this.enabled = true;
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public void setUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Username is required");
        }
        this.username = username.trim();
    }

    public void setPasswordHash(String passwordHash) {
        if (passwordHash == null || passwordHash.isBlank()) {
            throw new IllegalArgumentException("Password hash is required");
        }
        this.passwordHash = passwordHash;
    }

    public void setRole(String role) {
        if (role == null) {
            throw new IllegalArgumentException("Role is required");
        }

        String normalizedRole = role.trim().toUpperCase(Locale.ROOT);

        if (!ALLOWED_ROLES.contains(normalizedRole)) {
            throw new IllegalArgumentException("Invalid admin role");
        }

        this.role = normalizedRole;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getRole() {
        return role;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
