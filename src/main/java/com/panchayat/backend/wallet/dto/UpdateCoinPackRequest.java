
        package com.panchayat.backend.wallet.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record UpdateCoinPackRequest(

        @Size(min = 1, max = 100)
        String name,

        @Positive
        Long coins,

        @DecimalMin("0.01")
        @Digits(integer = 8, fraction = 2)
        BigDecimal priceInRupees,

        @PositiveOrZero
        Long bonusCoins,

        @Min(0)
        @Max(100)
        Integer bonusPercentage,

        @Size(max = 30)
        String badge,

        @Min(0)
        Integer sortOrder
) {}
