
        package com.panchayat.backend.voice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record MuteParticipantRequest(

        @NotNull(message = "User ID is required")
        @Positive(message = "User ID must be positive")
        Long userId,

        @NotBlank(message = "Track SID is required")
        String trackSid,

        @NotNull(message = "Muted status is required")
        Boolean muted

) {}
