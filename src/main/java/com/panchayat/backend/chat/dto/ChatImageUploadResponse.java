
        package com.panchayat.backend.chat.dto;

import java.time.Instant;

public record ChatImageUploadResponse(
        Long messageId,
        Long conversationId,
        String messageType,
        Instant createdAt
) {
}
