
        package com.panchayat.backend.wallet.service;

import com.panchayat.backend.auth.entity.User;
import com.panchayat.backend.auth.repository.UserRepository;
import com.panchayat.backend.wallet.dto.AdminRedPacketGrantRequest;
import com.panchayat.backend.wallet.dto.AdminRedPacketInventoryResponse;
import com.panchayat.backend.wallet.entity.RedPacketProduct;
import com.panchayat.backend.wallet.entity.UserRedPacketInventory;
import com.panchayat.backend.wallet.repository.RedPacketProductRepository;
import com.panchayat.backend.wallet.repository.UserRedPacketInventoryRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class AdminRedPacketInventoryService {

    private final UserRepository userRepository;
    private final RedPacketProductRepository productRepository;
    private final UserRedPacketInventoryRepository inventoryRepository;

    public AdminRedPacketInventoryService(
            UserRepository userRepository,
            RedPacketProductRepository productRepository,
            UserRedPacketInventoryRepository inventoryRepository
    ) {
        this.userRepository = userRepository;
        this.productRepository = productRepository;
        this.inventoryRepository = inventoryRepository;
    }

    @Transactional
    public AdminRedPacketInventoryResponse grant(
            Long userId,
            AdminRedPacketGrantRequest request
    ) {
        if (userId == null || userId <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Invalid user ID"
            );
        }

        User user = userRepository.findByIdForWalletUpdate(userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "User not found"
                ));

        RedPacketProduct product = productRepository.findById(request.productId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Red packet product not found"
                ));

        UserRedPacketInventory inventory =
                inventoryRepository.findForUpdate(userId, product.getId())
                        .orElse(null);

        if (inventory == null) {
            inventory = new UserRedPacketInventory();
            inventory.setUser(user);
            inventory.setRedPacketProduct(product);
            inventory.setQuantity(0);
        }

        try {
            inventory.setQuantity(
                    Math.addExact(inventory.getQuantity(), request.quantity())
            );
        } catch (ArithmeticException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Red packet quantity exceeds the supported limit"
            );
        }

        UserRedPacketInventory saved =
                inventoryRepository.saveAndFlush(inventory);

        return AdminRedPacketInventoryResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<AdminRedPacketInventoryResponse> getInventory(Long userId) {
        if (userId == null || userId <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Invalid user ID"
            );
        }

        if (!userRepository.existsById(userId)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "User not found"
            );
        }

        return inventoryRepository.findAllByUserId(userId)
                .stream()
                .map(AdminRedPacketInventoryResponse::from)
                .toList();
    }
}
