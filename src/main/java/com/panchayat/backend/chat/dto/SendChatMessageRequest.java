
        package com.panchayat.backend.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SendChatMessageRequest(

        @NotBlank(message = "Message cannot be empty")
        @Size(max = 5000, message = "Message cannot exceed 5000 characters")
        String message

) {
}
