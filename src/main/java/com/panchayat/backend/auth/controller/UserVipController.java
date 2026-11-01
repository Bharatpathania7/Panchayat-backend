
        package com.panchayat.backend.auth.controller;

import com.panchayat.backend.auth.dto.AdminVipResponse;
import com.panchayat.backend.auth.entity.User;
import com.panchayat.backend.auth.repository.UserRepository;
import com.panchayat.backend.auth.service.AdminVipService;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/vip")
@PreAuthorize("hasRole('USER')")
public class UserVipController {

    private final UserRepository userRepository;
    private final AdminVipService adminVipService;

    public UserVipController(
            UserRepository userRepository,
            AdminVipService adminVipService
    ) {
        this.userRepository = userRepository;
        this.adminVipService = adminVipService;
    }

    @GetMapping("/me")
    public AdminVipResponse getMyVipStatus(
            Authentication authentication
    ) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication.getName() == null
                || authentication.getName().isBlank()) {

            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Authentication required"
            );
        }

        String email = authentication.getName();

        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "User not found"
                ));

        return adminVipService.getVipStatus(user.getId());
    }
}
