package com.panchayat.backend.voice.dto;

import com.panchayat.backend.voice.entity.VoiceRoomType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateVoiceRoomRequest(

        @NotBlank(message = "Room name is required")
        @Size(max = 100, message = "Room name cannot exceed 100 characters")
        String name,

        @Size(max = 500, message = "Description cannot exceed 500 characters")
        String description,

        @NotNull(message = "Room type is required")
        VoiceRoomType roomType,

        boolean isPrivate,

        @Min(value = 2, message = "Minimum capacity is 2")
        @Max(value = 50, message = "Maximum capacity is 50")
        int maxParticipants

) {}