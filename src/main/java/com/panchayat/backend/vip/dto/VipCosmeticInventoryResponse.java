
        package com.panchayat.backend.vip.dto;

import com.panchayat.backend.vip.entity.VipCosmetic;

import java.time.Instant;

public record VipCosmeticInventoryResponse(
        Long id,
        Integer vipLevel,
        VipCosmetic.CosmeticType cosmeticType,
        VipCosmetic.CosmeticFormat format,
        String url,
        String originalFilename,
        Instant unlockedAt,
        boolean equipped
) {}