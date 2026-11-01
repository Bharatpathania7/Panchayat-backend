
        package com.panchayat.backend.wallet.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record AdminRedPacketGrantRequest(
        @NotNull
        @Positive
        Long productId,

        @NotNull
        @Positive
        Long quantity
) {}
