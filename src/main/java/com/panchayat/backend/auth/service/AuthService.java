
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

    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private DeviceGuard deviceGuard;


    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private com.panchayat.backend.rewards.NetworkGuard networkGuard;


    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private com.panchayat.backend.newbie.NewbieService newbieService;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate welcomeJdbc;

    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private com.panchayat.backend.sarpanch.SarpanchService sarpanch;


    private static final int OTP_EXPIRATION_SECONDS = 120;
    private static final int MAX_OTP_ATTEMPTS = 5;

    private final UserRepository userRepository;
    private final EmailOtpRepository emailOtpRepository;
    private final EmailService emailService;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;

    private final BCryptPasswordEncoder passwordEncoder =
            new BCryptPasswordEncoder();

    private final SecureRandom secureRandom = new SecureRandom();

    /*
     * Store-review account. When BOTH are set in the server environment
     * (PANCHAYAT_REVIEW_EMAIL / PANCHAYAT_REVIEW_OTP), that one email gets the fixed
     * code instead of an emailed one, so a Google Play reviewer can sign in. Leave
     * them empty to switch it off. The account is an ordinary user (no coins, no admin).
     */
    @org.springframework.beans.factory.annotation.Value("${panchayat.review.email:}")
    private String reviewEmail;

    @org.springframework.beans.factory.annotation.Value("${panchayat.review.otp:}")
    private String reviewOtp;

    private boolean isReviewAccount(String email) {
        return reviewEmail != null && !reviewEmail.isBlank()
                && reviewOtp != null && reviewOtp.matches("[0-9]{6}")
                && email.equalsIgnoreCase(reviewEmail.trim());
    }

    public AuthService(
            UserRepository userRepository,
            EmailOtpRepository emailOtpRepository,
            EmailService emailService,
            JwtService jwtService,
            RefreshTokenService refreshTokenService
    ) {
        this.userRepository = userRepository;
        this.emailOtpRepository = emailOtpRepository;
        this.emailService = emailService;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
    }

    /*
     * STEP 1: Send a six-digit OTP to the user's email.
     * Existing OTP records are updated instead of duplicated.
     */
    @Transactional
    public void sendOtp(SendOtpRequest request) {

        String email = normalizeEmail(request.email());

        // A new account on a phone that already has one: say so now, before a code is sent
        if (userRepository.findByEmailIgnoreCase(email).isEmpty()) deviceGuard.assertCanOpenAccount();

        boolean review = isReviewAccount(email);
        String otp = review ? reviewOtp : String.format(
                Locale.ROOT,
                "%06d",
                secureRandom.nextInt(1_000_000)
        );

        Instant now = Instant.now();

        // Find the existing record or create one if it doesn't exist.
        EmailOtp emailOtp = emailOtpRepository
                .findByEmailIgnoreCase(email)
                .orElseGet(EmailOtp::new);

        // Replace the old OTP and reset its expiration and attempts.
        emailOtp.setEmail(email);
        emailOtp.setOtpHash(passwordEncoder.encode(otp));
        emailOtp.setExpiresAt(now.plusSeconds(OTP_EXPIRATION_SECONDS));
        emailOtp.setAttempts(0);

        // Save the existing record or insert a new one.
        emailOtpRepository.saveAndFlush(emailOtp);

        // Send the new OTP using the configured email service (the review account gets none).
        if (!review) emailService.sendOtp(email, otp);
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

        // Reject OTPs after the maximum number of failed attempts.
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
        boolean[] created = {false};
        User user = userRepository
                .findByEmailIgnoreCase(email)
                .orElseGet(() -> {
                    deviceGuard.assertCanOpenAccount();
                    User newUser = new User();
                    newUser.setEmail(email);
                    created[0] = true;
                    return newUser;
                });

        user.setEmailVerified(true);
        user = userRepository.saveAndFlush(user);
        deviceGuard.remember(user.getId());
        if (created[0]) newbieService.onRegistered(user.getId());

        return refreshTokenService.signIn(user, "Email verified successfully");
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

        if (request.fullName() == null ||
                request.fullName().isBlank()) {
            throw new AuthException("Full name is required");
        }

        if (request.gender() == null ||
                request.gender().isBlank()) {
            throw new AuthException("Gender is required");
        }

        String fullName = request.fullName().trim();
        String gender = request.gender().trim();

        user.setFullName(fullName);
        user.setGender(gender);
        user.setDateOfBirth(dateOfBirth);
        boolean firstTime = !user.isProfileCompleted();
        user.setProfileCompleted(true);

        // Written to the database now: the VIP trial below is a direct update of the same row, and the entity must not be flushed over it at commit
        User saved = userRepository.saveAndFlush(user);
        if (firstTime) {
            // New user: Sarpanch's welcome red packet (the amount is set in the admin panel), opened from the message
            // ...but only for the first accounts from one network (the admin sets how many), so fake accounts don't farm it
            String network = networkGuard.currentNetwork();
            int cap = networkGuard.policy().maxWelcomePerNetwork();
            boolean offerGift = true;
            if (network != null) {
                welcomeJdbc.update("update users set signup_ip = ? where id = ?", network, saved.getId());
                Integer already = welcomeJdbc.queryForObject("select count(*) from users where signup_ip = ? and welcome_gift_offered and id <> ?", Integer.class, network, saved.getId());
                offerGift = cap == 0 || already == null || already < cap;
            }
            welcomeJdbc.update("update users set welcome_gift_offered = ? where id = ?", offerGift, saved.getId());
            // Every new user gets 3 days of free VIP 1 (then they can buy VIP)
            // (how many days is set in the admin panel: VIP management)
            Integer trialDays = welcomeJdbc.query("select coalesce((select free_trial_days from vip_settings where id = 1), 3)", rs -> rs.next() ? rs.getInt(1) : 3);
            java.sql.Timestamp trialEnds = welcomeJdbc.query("update users set vip_active = true, vip_level = greatest(coalesce(vip_level, 0), 1),"
                    + " vip_expires_at = now() + (coalesce((select free_trial_days from vip_settings where id = 1), 3) * interval '1 day')"
                    + " where id = ? and coalesce((select free_trial_days from vip_settings where id = 1), 3) > 0"
                    + " and not (vip_active and vip_expires_at is not null and vip_expires_at > now()) returning vip_expires_at",
                    rs -> rs.next() ? rs.getTimestamp(1) : null, saved.getId());
            // Remember who got the free trial, so the admin panel can show it
            if (trialEnds != null) {
                welcomeJdbc.update("insert into vip_trials (user_id, days, ends_at) values (?, ?, ?) on conflict (user_id) do nothing", saved.getId(), trialDays == null ? 3 : trialDays, trialEnds);
            }
            long[] rates = welcomeJdbc.query("select inviter_reward, welcome_gift from referral_settings where id = 1", rs -> rs.next() ? new long[]{rs.getLong(1), rs.getLong(2)} : new long[]{20_000, 50_000});
            java.text.NumberFormat inr = java.text.NumberFormat.getInstance(new java.util.Locale("en", "IN"));
            if (offerGift) sarpanch.notifySafely(saved.getId(), "WELCOME_GIFT", java.util.Map.of("name", fullName, "reward", inr.format(rates[0]), "gift", inr.format(rates[1])), "welcome:" + saved.getId());
        }
        return saved;
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
