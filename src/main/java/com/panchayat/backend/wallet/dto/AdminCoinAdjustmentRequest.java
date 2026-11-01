
        package com.panchayat.backend.wallet.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record AdminCoinAdjustmentRequest(

        @NotNull
        @Positive
        Long amount,

        @NotBlank
        @Size(max = 40)
        String reason,

        @Size(max = 100)
        String referenceId,

        @NotBlank
        @Size(max = 100)
        String idempotencyKey,

        @Size(max = 500)
        String description
) {
}
