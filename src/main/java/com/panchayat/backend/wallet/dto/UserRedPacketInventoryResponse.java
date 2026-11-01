
        package com.panchayat.backend.wallet.dto;

import com.panchayat.backend.wallet.entity.UserRedPacketInventory;

public record UserRedPacketInventoryResponse(
        Long inventoryId,
        Long productId,
        String name,
        long coinValue,
        String imageUrl,
        long quantity
) {

    public static UserRedPacketInventoryResponse from(
            UserRedPacketInventory inventory) {

        var product = inventory.getRedPacketProduct();

        return new UserRedPacketInventoryResponse(
                inventory.getId(),
                product.getId(),
                product.getName(),
                product.getCoinValue(),
                product.getImageUrl(),
                inventory.getQuantity()
        );
    }
}
