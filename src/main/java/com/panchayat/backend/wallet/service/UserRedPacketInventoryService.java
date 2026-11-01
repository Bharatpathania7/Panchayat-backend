
        package com.panchayat.backend.wallet.service;

import com.panchayat.backend.auth.entity.User;
import com.panchayat.backend.auth.repository.UserRepository;
import com.panchayat.backend.wallet.entity.UserRedPacketInventory;
import com.panchayat.backend.wallet.repository.UserRedPacketInventoryRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class UserRedPacketInventoryService {

    private final UserRedPacketInventoryRepository inventoryRepository;
    private final UserRepository userRepository;

    public UserRedPacketInventoryService(
            UserRedPacketInventoryRepository inventoryRepository,
            UserRepository userRepository) {
        this.inventoryRepository = inventoryRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public List<UserRedPacketInventory> getMyInventory(String email) {

        if (email == null || email.isBlank()) {
            throw new AccessDeniedException("Authentication is required");
        }

        User user = userRepository.findByEmailIgnoreCase(email.trim())
                .orElseThrow(() ->
                        new IllegalArgumentException("Authenticated user not found"));

        return inventoryRepository.findAllByUserId(user.getId());
    }
}
