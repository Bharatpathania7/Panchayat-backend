
        package com.panchayat.backend.auth.SecurityConfig;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Date;
import java.util.Locale;
import java.util.Set;

@Service
public class JwtService {

    private final SecretKey secretKey;
    private final long expirationMs;

    private static final Set<String> ALLOWED_ACCOUNT_TYPES = Set.of(
            "USER",
            "ADMIN",
            "SUPER_ADMIN",
            "MODERATOR"
    );

    public JwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.expiration-ms}") long expirationMs
    ) {
        byte[] keyBytes = Base64.getDecoder().decode(secret);
        this.secretKey = Keys.hmacShaKeyFor(keyBytes);

        if (expirationMs <= 0) {
            throw new IllegalArgumentException(
                    "app.jwt.expiration-ms must be greater than zero"
            );
        }

        this.expirationMs = expirationMs;
    }

    /**
     * Generate a token for a normal user.
     */
    public String generateToken(Long userId, String email) {
        return generateToken(userId, email, "USER");
    }

    /**
     * Generate a JWT for a specific account type.
     *
     * Supported account types:
     * USER, ADMIN, SUPER_ADMIN, MODERATOR.
     */
    public String generateToken(
            Long accountId,
            String identity,
            String accountType
    ) {
        if (accountId == null || accountId <= 0) {
            throw new IllegalArgumentException(
                    "Account ID must be a positive number"
            );
        }

        if (identity == null || identity.isBlank()) {
            throw new IllegalArgumentException(
                    "Account identity cannot be empty"
            );
        }

        if (accountType == null || accountType.isBlank()) {
            throw new IllegalArgumentException(
                    "Account type cannot be empty"
            );
        }

        String normalizedAccountType = accountType
                .trim()
                .toUpperCase(Locale.ROOT);

        if (!ALLOWED_ACCOUNT_TYPES.contains(normalizedAccountType)) {
            throw new IllegalArgumentException(
                    "Unsupported account type: " + normalizedAccountType
            );
        }

        Date now = new Date();
        Date expiration = new Date(
                now.getTime() + expirationMs
        );

        return Jwts.builder()
                .subject(String.valueOf(accountId))
                .claim("email", identity.trim())
                .claim("accountType", normalizedAccountType)
                .issuedAt(now)
                .expiration(expiration)
                .signWith(secretKey)
                .compact();
    }

    /**
     * Extract and validate the account ID.
     */
    public Long extractUserId(String token) {
        Claims claims = extractAllClaims(token);

        Long accountId = Long.parseLong(claims.getSubject());

        if (accountId <= 0) {
            throw new IllegalArgumentException(
                    "Invalid account ID in JWT"
            );
        }

        return accountId;
    }

    /**
     * Extract email or username.
     */
    public String extractEmail(String token) {
        return extractAllClaims(token)
                .get("email", String.class);
    }

    /**
     * Extract the account type.
     */
    public String extractAccountType(String token) {
        String accountType = extractAllClaims(token)
                .get("accountType", String.class);

        if (accountType == null) {
            return null;
        }

        return accountType.toUpperCase(Locale.ROOT);
    }

    /**
     * Validate signature, expiration and required claims.
     */
    public boolean isValid(String token) {
        try {
            Claims claims = extractAllClaims(token);

            String subject = claims.getSubject();
            String identity = claims.get("email", String.class);
            String accountType = claims.get("accountType", String.class);

            if (subject == null || subject.isBlank()
                    || identity == null || identity.isBlank()
                    || accountType == null || accountType.isBlank()) {
                return false;
            }

            long accountId = Long.parseLong(subject);

            if (accountId <= 0) {
                return false;
            }

            String normalizedAccountType = accountType
                    .toUpperCase(Locale.ROOT);

            return ALLOWED_ACCOUNT_TYPES.contains(
                    normalizedAccountType
            );

        } catch (JwtException | IllegalArgumentException ex) {
            return false;
        }
    }

    /**
     * Parse the JWT and verify its signature and expiration.
     */
    private Claims extractAllClaims(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException(
                    "JWT token cannot be empty"
            );
        }

        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
