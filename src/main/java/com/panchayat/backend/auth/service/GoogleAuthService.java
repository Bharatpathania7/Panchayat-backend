package com.panchayat.backend.auth.service;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;

import com.panchayat.backend.auth.dto.AuthResponse;
import com.panchayat.backend.auth.entity.User;
import com.panchayat.backend.auth.repository.UserRepository;
import com.panchayat.backend.auth.SecurityConfig.JwtService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Locale;

@Service
public class GoogleAuthService {

    private final GoogleIdTokenVerifier googleIdTokenVerifier;
    private final UserRepository userRepository;
    private final JwtService jwtService;

    public GoogleAuthService(
            GoogleIdTokenVerifier googleIdTokenVerifier,
            UserRepository userRepository,
            JwtService jwtService
    ) {
        this.googleIdTokenVerifier = googleIdTokenVerifier;
        this.userRepository = userRepository;
        this.jwtService = jwtService;
    }

    @Transactional
    public AuthResponse authenticate(String idToken) {

        final GoogleIdToken googleIdToken;

        try {
            googleIdToken = googleIdTokenVerifier.verify(idToken);
        } catch (GeneralSecurityException | IOException exception) {
            throw new AuthService.AuthException(
                    "Unable to verify Google identity token"
            );
        }

        if (googleIdToken == null) {
            throw new AuthService.AuthException(
                    "Invalid Google ID token"
            );
        }

        GoogleIdToken.Payload payload = googleIdToken.getPayload();

        String googleEmail = payload.getEmail();

        if (googleEmail == null || googleEmail.isBlank()) {
            throw new AuthService.AuthException(
                    "Google account does not contain an email address"
            );
        }

        if (!Boolean.TRUE.equals(payload.getEmailVerified())) {
            throw new AuthService.AuthException(
                    "Your Google email address is not verified"
            );
        }

        String email = googleEmail.trim().toLowerCase(Locale.ROOT);

        // Find the existing account or create a new one.
        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseGet(() -> {
                    User newUser = new User();
                    newUser.setEmail(email);
                    newUser.setEmailVerified(true);
                    return newUser;
                });

        user.setEmailVerified(true);

        user = userRepository.save(user);

        // Generate our own Panchayat JWT.
        String accessToken = jwtService.generateToken(
                user.getId(),
                user.getEmail()
        );

        return new AuthResponse(
                "Google sign-in successful",
                accessToken,
                "Bearer",
                user.isProfileCompleted()
        );
    }
}