package com.panchayat.backend.wallet.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record CreateRedPacketRequest(

        @NotBlank
        @Size(max = 100)
        String name,

        @NotNull
        @Positive
        Long coinValue,

        @NotNull
        @DecimalMin("0.01")
        @Digits(integer = 8, fraction = 2)
        BigDecimal priceInRupees,

        @Size(max = 1000)
        String imageUrl,

        Boolean active,

        @Min(0)
        Integer sortOrder
) {}