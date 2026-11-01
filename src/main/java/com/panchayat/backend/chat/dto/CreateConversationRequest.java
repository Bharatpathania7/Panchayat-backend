
        package com.panchayat.backend.chat.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateConversationRequest(

        @NotNull(message = "Recipient user ID is required")
        @Positive(message = "Recipient user ID must be positive")
        Long recipientUserId

) {
}
