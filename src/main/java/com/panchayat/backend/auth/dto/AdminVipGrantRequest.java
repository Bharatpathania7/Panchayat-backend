package com.panchayat.backend.auth.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record AdminVipGrantRequest(

        @NotNull
        @Min(1)
        @Max(3650)
        Integer durationDays

) {}