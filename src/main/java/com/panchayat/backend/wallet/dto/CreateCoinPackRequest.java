package com.panchayat.backend.wallet.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record CreateCoinPackRequest(

        @NotBlank(message = "Pack name is required")
        @Size(max = 100)
        String name,

        @NotNull
        @Positive(message = "Coins must be greater than zero")
        Long coins,

        @NotNull
        @DecimalMin(value = "0.01")
        @Digits(integer = 8, fraction = 2)
        BigDecimal priceInRupees,

        @NotNull
        @PositiveOrZero
        Long bonusCoins,

        @NotNull
        @Min(0)
        @Max(100)
        Integer bonusPercentage,

        @Size(max = 30)
        String badge,

        Boolean active,

        @Min(0)
        Integer sortOrder
) {
        public Boolean activeOrDefault() {
                return active != null ? active : true;
        }
}