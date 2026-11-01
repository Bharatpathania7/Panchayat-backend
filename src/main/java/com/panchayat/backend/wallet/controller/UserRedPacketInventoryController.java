
        package com.panchayat.backend.wallet.controller;

import com.panchayat.backend.wallet.dto.UserRedPacketInventoryResponse;
import com.panchayat.backend.wallet.service.UserRedPacketInventoryService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/wallet/me/red-packets")
public class UserRedPacketInventoryController {

    private final UserRedPacketInventoryService inventoryService;

    public UserRedPacketInventoryController(
            UserRedPacketInventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @GetMapping("/inventory")
    public List<UserRedPacketInventoryResponse> getMyInventory(
            Authentication authentication) {

        if (authentication == null || !authentication.isAuthenticated()) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Authentication is required"
            );
        }

        return inventoryService.getMyInventory(authentication.getName())
                .stream()
                .map(UserRedPacketInventoryResponse::from)
                .toList();
    }
}
