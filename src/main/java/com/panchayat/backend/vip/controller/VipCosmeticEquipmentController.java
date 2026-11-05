
        package com.panchayat.backend.vip.controller;

import com.panchayat.backend.auth.entity.User;
import com.panchayat.backend.auth.repository.UserRepository;
import com.panchayat.backend.vip.service.VipCosmeticEquipmentService;
import com.panchayat.backend.vip.service.VipCosmeticEquipmentService.EquippedCosmeticResponse;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/vip/cosmetics")
public class VipCosmeticEquipmentController {

    private final VipCosmeticEquipmentService equipmentService;
    private final UserRepository userRepository;

    public VipCosmeticEquipmentController(
            VipCosmeticEquipmentService equipmentService,
            UserRepository userRepository
    ) {
        this.equipmentService = equipmentService;
        this.userRepository = userRepository;
    }

    @PostMapping("/{cosmeticId}/wear")
    public ResponseEntity<EquippedCosmeticResponse> equip(
            @PathVariable Long cosmeticId,
            Authentication authentication
    ) {
        Long userId = getAuthenticatedUserId(authentication);

        EquippedCosmeticResponse response =
                equipmentService.equip(userId, cosmeticId);

        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{cosmeticId}/wear")
    public ResponseEntity<Void> unequip(
            @PathVariable Long cosmeticId,
            Authentication authentication
    ) {
        Long userId = getAuthenticatedUserId(authentication);

        equipmentService.unequip(userId, cosmeticId);

        return ResponseEntity.noContent().build();
    }

    @GetMapping("/equipped")
    public ResponseEntity<List<EquippedCosmeticResponse>> getEquipped(
            Authentication authentication
    ) {
        Long userId = getAuthenticatedUserId(authentication);

        return ResponseEntity.ok(
                equipmentService.getEquipped(userId)
        );
    }

    private Long getAuthenticatedUserId(
            Authentication authentication
    ) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication.getName() == null
                || authentication.getName().isBlank()) {
            throw new IllegalStateException(
                    "Authenticated user is required"
            );
        }

        String email = authentication.getName();

        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Authenticated user not found"
                        ));

        return user.getId();
    }

    // Clear answers instead of a 500: the app shows these messages as they are
    @org.springframework.web.bind.annotation.ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<java.util.Map<String, String>> status(org.springframework.web.server.ResponseStatusException e) {
        return org.springframework.http.ResponseEntity.status(e.getStatusCode()).body(java.util.Map.of("message", e.getReason() == null ? "That didn't work" : e.getReason()));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalArgumentException.class)
    public org.springframework.http.ResponseEntity<java.util.Map<String, String>> bad(IllegalArgumentException e) {
        return org.springframework.http.ResponseEntity.badRequest().body(java.util.Map.of("message", e.getMessage() == null ? "That isn't valid" : e.getMessage()));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalStateException.class)
    public org.springframework.http.ResponseEntity<java.util.Map<String, String>> conflict(IllegalStateException e) {
        return org.springframework.http.ResponseEntity.status(409).body(java.util.Map.of("message", e.getMessage() == null ? "That can't be done right now" : e.getMessage()));
    }
}
