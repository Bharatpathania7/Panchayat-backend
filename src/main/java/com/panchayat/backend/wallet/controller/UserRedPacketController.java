
        package com.panchayat.backend.wallet.controller;

import com.panchayat.backend.auth.entity.User;
import com.panchayat.backend.auth.repository.UserRepository;
import com.panchayat.backend.wallet.dto.UserRedPacketInventoryResponse;
import com.panchayat.backend.wallet.repository.UserRedPacketInventoryRepository;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/v1/red-packets")
@PreAuthorize("hasRole('USER')")
public class UserRedPacketController {

    private final UserRepository userRepository;
    private final UserRedPacketInventoryRepository inventoryRepository;

    public UserRedPacketController(
            UserRepository userRepository,
            UserRedPacketInventoryRepository inventoryRepository
    ) {
        this.userRepository = userRepository;
        this.inventoryRepository = inventoryRepository;
    }

    @GetMapping("/inventory")
    public List<UserRedPacketInventoryResponse> getMyInventory(
            Authentication authentication
    ) {
        if (authentication == null || !authentication.isAuthenticated()) {
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

        return inventoryRepository.findAllByUserId(user.getId())
                .stream()
                .map(UserRedPacketInventoryResponse::from)
                .toList();
    }
}
