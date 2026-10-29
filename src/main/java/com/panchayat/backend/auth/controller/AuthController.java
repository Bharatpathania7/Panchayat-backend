
        package com.panchayat.backend.auth.controller;

import com.panchayat.backend.auth.dto.AuthResponse;
import com.panchayat.backend.auth.dto.CompleteProfileRequest;
import com.panchayat.backend.auth.dto.GoogleAuthRequest;
import com.panchayat.backend.auth.dto.SendOtpRequest;
import com.panchayat.backend.auth.dto.VerifyOtpRequest;
import com.panchayat.backend.auth.entity.User;
import com.panchayat.backend.auth.service.AuthService;
import com.panchayat.backend.auth.service.GoogleAuthService;

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

    public AuthController(
            AuthService authService,
            GoogleAuthService googleAuthService
    ) {
        this.authService = authService;
        this.googleAuthService = googleAuthService;
    }

    /*
     * STEP 1: Send OTP to email.
     * POST /api/v1/auth/send-otp
     */
    @PostMapping("/send-otp")
    public ResponseEntity<Map<String, String>> sendOtp(
            @Valid @RequestBody SendOtpRequest request
    ) {
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
