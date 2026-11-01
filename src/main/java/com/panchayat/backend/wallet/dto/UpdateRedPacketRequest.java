
        package com.panchayat.backend.wallet.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record UpdateRedPacketRequest(

        @Size(min = 1, max = 100)
        String name,

        @Positive
        Long coinValue,

        @DecimalMin("0.01")
        @Digits(integer = 8, fraction = 2)
        BigDecimal priceInRupees,

        @Size(max = 1000)
        String imageUrl,

        @Min(0)
        Integer sortOrder
) {}
