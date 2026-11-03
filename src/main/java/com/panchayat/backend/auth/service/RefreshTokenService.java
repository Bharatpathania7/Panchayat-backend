package com.panchayat.backend.auth.service;

import com.panchayat.backend.auth.SecurityConfig.JwtService;
import com.panchayat.backend.auth.dto.AuthResponse;
import com.panchayat.backend.auth.entity.RefreshToken;
import com.panchayat.backend.auth.entity.User;
import com.panchayat.backend.auth.repository.RefreshTokenRepository;
import com.panchayat.backend.auth.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Sign-in sessions that outlive the short access token.
 *
 * - Sign-in returns an access token (minutes) and a refresh token (days).
 * - POST /auth/refresh swaps a refresh token for a new pair; the old refresh
 *   token stops working (rotation).
 * - Only a SHA-256 hash is stored, so a database leak doesn't leak sessions.
 * - A token used again after it was rotated means someone copied it: every
 *   session of that user is ended. Within a short grace period it's treated
 *   as two tabs refreshing at once and simply refused.
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final Duration REUSE_GRACE = Duration.ofSeconds(60);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenRepository repository;
    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final Duration lifetime;

    public RefreshTokenService(
            RefreshTokenRepository repository,
            UserRepository userRepository,
            JwtService jwtService,
            @Value("${app.jwt.refresh-days:30}") long refreshDays
    ) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.lifetime = Duration.ofDays(Math.max(1, refreshDays));
    }

    /** The response for a successful sign-in: a new access token and a new session. */
    @Transactional
    public AuthResponse signIn(User user, String message) {
        String accessToken = jwtService.generateToken(user.getId(), user.getEmail());
        String refreshToken = issue(user.getId());
        return new AuthResponse(
                message,
                accessToken,
                "Bearer",
                user.isProfileCompleted(),
                refreshToken,
                jwtService.getExpirationMs() / 1000
        );
    }

    /** Swap a refresh token for a new access token and a new refresh token. */
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public AuthResponse refresh(String rawToken) {
        if (rawToken == null || rawToken.isBlank() || rawToken.length() > 200) {
            throw unauthorized();
        }
        Instant now = Instant.now();
        RefreshToken token = repository.findByHashForUpdate(hash(rawToken))
                .orElseThrow(RefreshTokenService::unauthorized);

        if (token.isRevoked()) {
            if (token.getRevokedAt().plus(REUSE_GRACE).isBefore(now)) {
                // Used again long after rotation: it was copied. End every session.
                int ended = repository.revokeAllForUser(token.getUserId(), now);
                log.warn("Refresh token reuse detected; ended {} session(s) for userId={}", ended, token.getUserId());
            }
            throw unauthorized();
        }
        if (token.isExpired(now)) {
            token.revoke(now);
            throw unauthorized();
        }

        User user = userRepository.findById(token.getUserId())
                .orElseThrow(RefreshTokenService::unauthorized);

        token.revoke(now);
        repository.save(token);
        return signIn(user, "Session refreshed");
    }

    /** Sign out this device. Unknown tokens are ignored. */
    @Transactional
    public void revoke(String rawToken) {
        if (rawToken == null || rawToken.isBlank() || rawToken.length() > 200) return;
        repository.findByTokenHash(hash(rawToken)).ifPresent(t -> {
            t.revoke(Instant.now());
            repository.save(t);
        });
    }

    private String issue(Long userId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        repository.save(new RefreshToken(userId, hash(raw), Instant.now().plus(lifetime)));

        // Now and then, clear sessions that expired over a week ago
        if (ThreadLocalRandom.current().nextInt(50) == 0) {
            repository.deleteExpiredBefore(Instant.now().minus(Duration.ofDays(7)));
        }
        return raw;
    }

    private static String hash(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Your session has ended. Please sign in again.");
    }
}
