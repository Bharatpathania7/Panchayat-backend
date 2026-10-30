
        package com.panchayat.backend.admin.auth.controller;

import com.panchayat.backend.admin.auth.dto.AdminLoginRequest;
import com.panchayat.backend.admin.auth.dto.AdminLoginResponse;
import com.panchayat.backend.admin.auth.service.AdminAuthService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/auth")
public class AdminAuthController {

    private final AdminAuthService adminAuthService;

    public AdminAuthController(AdminAuthService adminAuthService) {
        this.adminAuthService = adminAuthService;
    }

    /**
     * Admin login endpoint.
     *
     * Supports:
     * SUPER_ADMIN
     * ADMIN
     * MODERATOR
     */
    @PostMapping("/login")
    public ResponseEntity<AdminLoginResponse> login(
            @Valid @RequestBody AdminLoginRequest request
    ) {
        AdminLoginResponse response = adminAuthService.login(request);

        return ResponseEntity
                .status(HttpStatus.OK)
                .body(response);
    }
}
