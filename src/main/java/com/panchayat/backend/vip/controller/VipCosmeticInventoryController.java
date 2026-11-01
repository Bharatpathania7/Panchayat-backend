
        package com.panchayat.backend.vip.controller;

import com.panchayat.backend.vip.dto.VipCosmeticInventoryResponse;
import com.panchayat.backend.vip.service.VipCosmeticInventoryService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/vip/cosmetics")
public class VipCosmeticInventoryController {

    private final VipCosmeticInventoryService inventoryService;

    public VipCosmeticInventoryController(
            VipCosmeticInventoryService inventoryService
    ) {
        this.inventoryService = inventoryService;
    }

    /**
     * Returns the authenticated user's unlocked cosmetic inventory.
     */
    @GetMapping("/inventory")
    public ResponseEntity<List<VipCosmeticInventoryResponse>> getInventory(
            Authentication authentication
    ) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication.getName() == null
                || authentication.getName().isBlank()) {

            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        boolean isUserRole = authentication.getAuthorities()
                .stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_USER"::equals);

        if (!isUserRole) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        List<VipCosmeticInventoryResponse> inventory =
                inventoryService.getInventory(authentication.getName());

        return ResponseEntity.ok(inventory);
    }
}
