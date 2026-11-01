package com.panchayat.backend.wallet.dto;

import com.panchayat.backend.wallet.entity.CoinPack;

import java.math.BigDecimal;

public record CoinPackResponse(
        Long id,
        String name,
        Long coins,
        BigDecimal priceInRupees,
        Long bonusCoins,
        Integer bonusPercentage,
        String badge,
        String imageUrl
) {
    public static CoinPackResponse from(CoinPack coinPack) {
        return new CoinPackResponse(
                coinPack.getId(),
                coinPack.getName(),
                coinPack.getCoins(),
                coinPack.getPriceInRupees(),
                coinPack.getBonusCoins(),
                coinPack.getBonusPercentage(),
                coinPack.getBadge(),
                coinPack.getImageUrl()
        );
    }
}