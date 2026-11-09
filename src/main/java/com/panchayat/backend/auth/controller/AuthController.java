
        package com.panchayat.backend.auth.controller;

import com.panchayat.backend.auth.dto.AuthResponse;
import com.panchayat.backend.auth.dto.CompleteProfileRequest;
import com.panchayat.backend.auth.dto.GoogleAuthRequest;
import com.panchayat.backend.auth.dto.SendOtpRequest;
import com.panchayat.backend.auth.dto.VerifyOtpRequest;
import com.panchayat.backend.auth.entity.User;
import com.panchayat.backend.auth.service.AuthService;
import com.panchayat.backend.auth.service.GoogleAuthService;
import com.panchayat.backend.auth.service.RefreshTokenService;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final GoogleAuthService googleAuthService;
    private final RefreshTokenService refreshTokenService;
    private final com.panchayat.backend.security.RateLimiter limiter;
    private final io.micrometer.core.instrument.MeterRegistry metrics;

    /** Codes one email address can be sent, whichever IP asks. */
    private static final int OTP_PER_EMAIL = 5;
    private static final long OTP_PER_EMAIL_WINDOW_MS = 10 * 60_000L;

    public AuthController(
            AuthService authService,
            GoogleAuthService googleAuthService,
            RefreshTokenService refreshTokenService,
            com.panchayat.backend.security.RateLimiter limiter,
            io.micrometer.core.instrument.MeterRegistry metrics
    ) {
        this.authService = authService;
        this.googleAuthService = googleAuthService;
        this.refreshTokenService = refreshTokenService;
        this.limiter = limiter;
        this.metrics = metrics;
    }

    /*
     * A wrong or expired code is the person's mistake, not a server failure: they see the
     * message (the app expects it), and it isn't logged as a crash or counted as a 5xx.
     */
    @ExceptionHandler(AuthService.AuthException.class)
    public ResponseEntity<Map<String, String>> authFailed(AuthService.AuthException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }

    public record RefreshRequest(String refreshToken) {
    }

    /*
     * Swap a refresh token for a new access token and refresh token.
     * POST /api/v1/auth/refresh  { "refreshToken": "..." }
     * 401 when the session ended (expired, signed out or already used).
     */
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@RequestBody(required = false) RefreshRequest request) {
        try {
            return ResponseEntity.ok(
                    refreshTokenService.refresh(request == null ? null : request.refreshToken())
            );
        } catch (org.springframework.web.server.ResponseStatusException e) {
            return ResponseEntity.status(e.getStatusCode()).body(
                    Map.of("message", e.getReason() == null ? "Please sign in again." : e.getReason())
            );
        }
    }

    /*
     * Sign out this device: the refresh token stops working.
     * POST /api/v1/auth/logout  { "refreshToken": "..." }
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestBody(required = false) RefreshRequest request) {
        refreshTokenService.revoke(request == null ? null : request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    /*
     * STEP 1: Send OTP to email.
     * POST /api/v1/auth/send-otp
     */
    @PostMapping("/send-otp")
    public ResponseEntity<Map<String, String>> sendOtp(
            @Valid @RequestBody SendOtpRequest request
    ) {
        long wait = limiter.hit(
                "otp-send-email:" + request.email().trim().toLowerCase(java.util.Locale.ROOT),
                OTP_PER_EMAIL,
                OTP_PER_EMAIL_WINDOW_MS
        );
        if (wait > 0) {
            metrics.counter("panchayat.auth.rate_limited", "rule", "otp-send-email").increment();
            return ResponseEntity.status(429)
                    .header("Retry-After", String.valueOf(wait))
                    .body(Map.of("message", "Too many codes for this email. Try again in "
                            + ((wait + 59) / 60) + " minutes."));
        }

        authService.sendOtp(request);

        return ResponseEntity.ok(
                Map.of("message", "OTP sent successfully")
        );
    }

    /*
     * STEP 2: Verify OTP and generate Panchayat JWT.
     * POST /api/v1/auth/verify-otp
     */
    @PostMapping("/verify-otp")
    public ResponseEntity<AuthResponse> verifyOtp(
            @Valid @RequestBody VerifyOtpRequest request
    ) {
        AuthResponse response = authService.verifyOtp(request);

        return ResponseEntity.ok(response);
    }

    /*
     * STEP 3: Sign in with Google.
     * POST /api/v1/auth/google
     */
    @PostMapping("/google")
    public ResponseEntity<AuthResponse> googleSignIn(
            @Valid @RequestBody GoogleAuthRequest request
    ) {
        AuthResponse response =
                googleAuthService.authenticate(request.idToken());

        return ResponseEntity.ok(response);
    }

    /*
     * STEP 4: Complete the authenticated user's profile.
     * POST /api/v1/auth/complete-profile
     */
    @PostMapping("/complete-profile")
    public ResponseEntity<Map<String, Object>> completeProfile(
            Authentication authentication,
            @Valid @RequestBody CompleteProfileRequest request
    ) {
        if (authentication == null ||
                !authentication.isAuthenticated()) {
            return ResponseEntity.status(401).body(
                    Map.of("error", "Authentication is required")
            );
        }

        User user = authService.completeProfile(
                authentication.getName(),
                request
        );

        return ResponseEntity.ok(
                Map.of(
                        "message", "Profile completed successfully",
                        "profileCompleted", user.isProfileCompleted()
                )
        );
    }
}
