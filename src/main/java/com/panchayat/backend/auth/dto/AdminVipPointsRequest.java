
        package com.panchayat.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record AdminVipPointsRequest(
        @NotNull
        @Positive
        Long amount,

        @NotBlank
        @Size(max = 200)
        String reason
) {}
