package com.panchayat.backend.auth.service;

import com.panchayat.backend.auth.dto.AdminVipGrantRequest;
import com.panchayat.backend.auth.dto.AdminVipLevelRequest;
import com.panchayat.backend.auth.dto.AdminVipPointsRequest;
import com.panchayat.backend.auth.dto.AdminVipResponse;
import com.panchayat.backend.auth.entity.User;
import com.panchayat.backend.auth.repository.UserRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
public class AdminVipService {

    private final UserRepository userRepository;
    private final VipLevelService vipLevelService;

    public AdminVipService(
            UserRepository userRepository,
            VipLevelService vipLevelService
    ) {
        this.userRepository = userRepository;
        this.vipLevelService = vipLevelService;
    }

    @Transactional
    public AdminVipResponse grantVip(
            Long userId,
            AdminVipGrantRequest request
    ) {
        User user = lockUser(userId);

        if (request == null || request.durationDays() == null
                || request.durationDays() < 1
                || request.durationDays() > 3650) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "VIP duration must be between 1 and 3650 days"
            );
        }

        Instant now = Instant.now();
        Instant currentExpiry = user.getVipExpiresAt();

        Instant baseTime = user.isVipActive()
                && currentExpiry != null
                && currentExpiry.isAfter(now)
                ? currentExpiry
                : now;

        Instant newExpiry = baseTime.plus(
                request.durationDays(),
                ChronoUnit.DAYS
        );

        user.setVipActive(true);
        user.setVipExpiresAt(newExpiry);

        refreshVipLevel(user);

        User savedUser = userRepository.save(user);

        return AdminVipResponse.from(savedUser);
    }

    @Transactional(readOnly = true)
    public AdminVipResponse getVipStatus(Long userId) {
        validateUserId(userId);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "User not found"
                ));

        return buildVipResponse(user);
    }

    @Transactional
    public AdminVipResponse extendVip(
            Long userId,
            AdminVipGrantRequest request
    ) {
        return grantVip(userId, request);
    }

    @Transactional
    public AdminVipResponse revokeVip(Long userId) {
        User user = lockUser(userId);

        user.setVipActive(false);
        user.setVipExpiresAt(null);

        // Preserve VIP points and assigned level.
        // Cosmetics visibility must check active VIP membership.
        refreshVipLevel(user);

        User savedUser = userRepository.save(user);

        return buildVipResponse(savedUser);
    }

    @Transactional
    public AdminVipResponse assignVipLevel(
            Long userId,
            AdminVipLevelRequest request
    ) {
        User user = lockUser(userId);

        if (request == null || request.vipLevel() == null
                || request.vipLevel() < 1
                || request.vipLevel() > 11) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "VIP level must be between 1 and 11"
            );
        }

        user.setAdminVipLevel(request.vipLevel());

        refreshVipLevel(user);

        userRepository.save(user);

        return buildVipResponse(user);
    }

    @Transactional
    public AdminVipResponse clearAdminVipLevel(Long userId) {
        User user = lockUser(userId);

        user.setAdminVipLevel(null);

        refreshVipLevel(user);

        userRepository.save(user);

        return buildVipResponse(user);
    }

    @Transactional
    public AdminVipResponse creditVipPoints(
            Long userId,
            AdminVipPointsRequest request
    ) {
        User user = lockUser(userId);

        if (request == null || request.amount() <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "VIP points amount must be greater than zero"
            );
        }

        long updatedPoints;

        try {
            updatedPoints = Math.addExact(
                    user.getVipPoints(),
                    request.amount()
            );
        } catch (ArithmeticException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "VIP points limit exceeded"
            );
        }

        user.setVipPoints(updatedPoints);

        refreshVipLevel(user);

        userRepository.save(user);

        return buildVipResponse(user);
    }

    @Transactional
    public AdminVipResponse debitVipPoints(
            Long userId,
            AdminVipPointsRequest request
    ) {
        User user = lockUser(userId);

        if (request == null || request.amount() <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "VIP points amount must be greater than zero"
            );
        }

        if (user.getVipPoints() < request.amount()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Insufficient VIP points"
            );
        }

        user.setVipPoints(
                user.getVipPoints() - request.amount()
        );

        refreshVipLevel(user);

        userRepository.save(user);

        return buildVipResponse(user);
    }

    private void refreshVipLevel(User user) {
        int effectiveLevel =
                vipLevelService.calculateEffectiveVipLevel(user);

        user.setVipLevel(effectiveLevel);
    }

    private AdminVipResponse buildVipResponse(User user) {
        int effectiveLevel =
                vipLevelService.calculateEffectiveVipLevel(user);

        boolean currentlyActive = user.isVipActive()
                && user.getVipExpiresAt() != null
                && user.getVipExpiresAt().isAfter(Instant.now());

        return new AdminVipResponse(
                user.getId(),
                currentlyActive,
                user.getVipExpiresAt(),
                user.getVipPoints(),
                effectiveLevel,
                user.getAdminVipLevel()
        );
    }

    private User lockUser(Long userId) {
        validateUserId(userId);

        return userRepository.findByIdForWalletUpdate(userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "User not found"
                ));
    }

    private void validateUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid user ID"
            );
        }
    }

}