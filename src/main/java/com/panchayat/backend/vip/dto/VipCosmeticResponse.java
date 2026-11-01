
        package com.panchayat.backend.vip.dto;

import com.panchayat.backend.vip.entity.VipCosmetic;

import java.time.Instant;

public record VipCosmeticResponse(
        Long id,
        Integer vipLevel,
        VipCosmetic.CosmeticType cosmeticType,
        VipCosmetic.CosmeticFormat format,
        String url,
        String originalFilename,
        Instant createdAt,
        Instant updatedAt
) {
    public static VipCosmeticResponse from(VipCosmetic cosmetic) {
        return new VipCosmeticResponse(
                cosmetic.getId(),
                cosmetic.getVipLevel(),
                cosmetic.getCosmeticType(),
                cosmetic.getFormat(),
                cosmetic.getSecureUrl(),
                cosmetic.getOriginalFilename(),
                cosmetic.getCreatedAt(),
                cosmetic.getUpdatedAt()
        );
    }
}
