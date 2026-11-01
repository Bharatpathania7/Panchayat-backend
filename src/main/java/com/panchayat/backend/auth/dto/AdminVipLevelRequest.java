package com.panchayat.backend.auth.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record AdminVipLevelRequest(
        @NotNull
        @Min(1)
        @Max(11)
        Integer vipLevel
) {
}