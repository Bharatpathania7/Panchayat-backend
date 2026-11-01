
        package com.panchayat.backend.wallet.dto;

import com.panchayat.backend.wallet.entity.UserRedPacketInventory;

import java.time.Instant;

public record AdminRedPacketInventoryResponse(
        Long inventoryId,
        Long userId,
        Long productId,
        String productName,
        Long coinValue,
        long quantity,
        Instant createdAt,
        Instant updatedAt
) {

    public static AdminRedPacketInventoryResponse from(
            UserRedPacketInventory inventory
    ) {
        return new AdminRedPacketInventoryResponse(
                inventory.getId(),
                inventory.getUser().getId(),
                inventory.getRedPacketProduct().getId(),
                inventory.getRedPacketProduct().getName(),
                inventory.getRedPacketProduct().getCoinValue(),
                inventory.getQuantity(),
                inventory.getCreatedAt(),
                inventory.getUpdatedAt()
        );
    }
}
