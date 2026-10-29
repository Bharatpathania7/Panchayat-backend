
        package com.panchayat.backend.auth.service;

import com.panchayat.backend.auth.dto.AuthResponse;
import com.panchayat.backend.auth.dto.CompleteProfileRequest;
import com.panchayat.backend.auth.dto.SendOtpRequest;
import com.panchayat.backend.auth.dto.VerifyOtpRequest;
import com.panchayat.backend.auth.entity.EmailOtp;
import com.panchayat.backend.auth.entity.User;
import com.panchayat.backend.auth.repository.EmailOtpRepository;
import com.panchayat.backend.auth.repository.UserRepository;
import com.panchayat.backend.auth.SecurityConfig.JwtService;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.Locale;

@Service
public class AuthService {

    private static final int OTP_EXPIRATION_SECONDS = 120;
    private static final int MAX_OTP_ATTEMPTS = 5;

    private final UserRepository userRepository;
    private final EmailOtpRepository emailOtpRepository;
    private final EmailService emailService;
    private final JwtService jwtService;

    private final BCryptPasswordEncoder passwordEncoder =
            new BCryptPasswordEncoder();

    private final SecureRandom secureRandom = new SecureRandom();

    public AuthService(
            UserRepository userRepository,
            EmailOtpRepository emailOtpRepository,
            EmailService emailService,
            JwtService jwtService
    ) {
        this.userRepository = userRepository;
        this.emailOtpRepository = emailOtpRepository;
        this.emailService = emailService;
        this.jwtService = jwtService;
    }

    /*
     * STEP 1: Send a six-digit OTP to the user's email.
     */
    @Transactional
    public void sendOtp(SendOtpRequest request) {

        String email = normalizeEmail(request.email());

        // Invalidate any previously issued OTP.
        emailOtpRepository.deleteByEmailIgnoreCase(email);

        String otp = String.format(
                Locale.ROOT,
                "%06d",
                secureRandom.nextInt(1_000_000)
        );

        EmailOtp emailOtp = new EmailOtp();
        emailOtp.setEmail(email);
        emailOtp.setOtpHash(passwordEncoder.encode(otp));
        emailOtp.setExpiresAt(
                Instant.now().plusSeconds(OTP_EXPIRATION_SECONDS)
        );
        emailOtp.setAttempts(0);

        emailOtpRepository.saveAndFlush(emailOtp);

        // Send the OTP using the configured email service.
        emailService.sendOtp(email, otp);
    }

    /*
     * STEP 2: Verify OTP and authenticate the user.
     */
    @Transactional(noRollbackFor = AuthException.class)
    public AuthResponse verifyOtp(VerifyOtpRequest request) {

        String email = normalizeEmail(request.email());

        EmailOtp emailOtp = emailOtpRepository
                .findByEmailIgnoreCase(email)
                .orElseThrow(() ->
                        new AuthException("Invalid or expired OTP")
                );

        // Reject expired OTPs.
        if (!Instant.now().isBefore(emailOtp.getExpiresAt())) {

            emailOtpRepository.delete(emailOtp);
            emailOtpRepository.flush();

            throw new AuthException(
                    "OTP has expired. Request a new OTP."
            );
        }

        // Limit the number of incorrect attempts.
        if (emailOtp.getAttempts() >= MAX_OTP_ATTEMPTS) {

            emailOtpRepository.delete(emailOtp);
            emailOtpRepository.flush();

            throw new AuthException(
                    "Too many incorrect attempts. Request a new OTP."
            );
        }

        boolean matches = passwordEncoder.matches(
                request.otp(),
                emailOtp.getOtpHash()
        );

        if (!matches) {

            emailOtp.setAttempts(emailOtp.getAttempts() + 1);
            emailOtpRepository.saveAndFlush(emailOtp);

            throw new AuthException(
                    "Invalid OTP. Please check your code and try again."
            );
        }

        // Delete the OTP after successful verification.
        emailOtpRepository.delete(emailOtp);
        emailOtpRepository.flush();

        // Find the existing user or create a new account.
        User user = userRepository
                .findByEmailIgnoreCase(email)
                .orElseGet(() -> {
                    User newUser = new User();
                    newUser.setEmail(email);
                    return newUser;
                });

        user.setEmailVerified(true);
        user = userRepository.saveAndFlush(user);

        String accessToken = jwtService.generateToken(
                user.getId(),
                user.getEmail()
        );

        return new AuthResponse(
                "Email verified successfully",
                accessToken,
                "Bearer",
                user.isProfileCompleted()
        );
    }

    /*
     * STEP 3: Complete the authenticated user's profile.
     */
    @Transactional
    public User completeProfile(
            String authenticatedEmail,
            CompleteProfileRequest request
    ) {

        String email = normalizeEmail(authenticatedEmail);

        User user = userRepository
                .findByEmailIgnoreCase(email)
                .orElseThrow(() ->
                        new AuthException("Authenticated user not found")
                );

        if (!user.isEmailVerified()) {
            throw new AuthException(
                    "Please verify your email first"
            );
        }

        LocalDate dateOfBirth = request.dateOfBirth();

        if (dateOfBirth == null ||
                !dateOfBirth.isBefore(LocalDate.now())) {

            throw new AuthException(
                    "Please provide a valid date of birth"
            );
        }

        int age = Period.between(
                dateOfBirth,
                LocalDate.now()
        ).getYears();

        if (age < 18) {
            throw new AuthException(
                    "You must be at least 18 years old to register"
            );
        }

        String fullName = request.fullName().trim();
        String gender = request.gender().trim();

        if (fullName.isEmpty()) {
            throw new AuthException("Full name is required");
        }

        if (gender.isEmpty()) {
            throw new AuthException("Gender is required");
        }

        user.setFullName(fullName);
        user.setGender(gender);
        user.setDateOfBirth(dateOfBirth);
        user.setProfileCompleted(true);

        return userRepository.save(user);
    }

    /*
     * Normalize email addresses consistently.
     */
    private String normalizeEmail(String email) {

        if (email == null || email.isBlank()) {
            throw new AuthException("Email is required");
        }

        return email.trim().toLowerCase(Locale.ROOT);
    }

    /*
     * Application-level authentication exception.
     */
    public static class AuthException extends RuntimeException {

        public AuthException(String message) {
            super(message);
        }
    }
}
