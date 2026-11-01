package com.panchayat.backend.auth.controller;
import com.panchayat.backend.auth.dto.AdminVipPointsRequest;
import com.panchayat.backend.auth.dto.AdminVipGrantRequest;
import com.panchayat.backend.auth.dto.AdminVipResponse;
import com.panchayat.backend.auth.service.AdminVipService;

import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/vip/users")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminVipController {

    private final AdminVipService adminVipService;

    public AdminVipController(AdminVipService adminVipService) {
        this.adminVipService = adminVipService;
    }

    @PostMapping("/{userId}/grant")
    public AdminVipResponse grantVip(
            @PathVariable Long userId,
            @Valid @RequestBody AdminVipGrantRequest request
    ) {
        return adminVipService.grantVip(userId, request);
    }

    @GetMapping("/{userId}")
    public AdminVipResponse getVipStatus(
            @PathVariable Long userId
    ) {
        return adminVipService.getVipStatus(userId);
    }
    @PatchMapping("/{userId}/extend")
    public AdminVipResponse extendVip(
            @PathVariable Long userId,
            @Valid @RequestBody AdminVipGrantRequest request
    ) {
        return adminVipService.extendVip(userId, request);
    }
    @DeleteMapping("/{userId}")
    public AdminVipResponse revokeVip(@PathVariable Long userId) {
        return adminVipService.revokeVip(userId);
    }
    @PostMapping("/{userId}/points/credit")
    public AdminVipResponse creditVipPoints(
            @PathVariable Long userId,
            @Valid @RequestBody AdminVipPointsRequest request
    ) {
        return adminVipService.creditVipPoints(userId, request);
    }

    @PostMapping("/{userId}/points/debit")
    public AdminVipResponse debitVipPoints(
            @PathVariable Long userId,
            @Valid @RequestBody AdminVipPointsRequest request
    ) {
        return adminVipService.debitVipPoints(userId, request);
    }
}