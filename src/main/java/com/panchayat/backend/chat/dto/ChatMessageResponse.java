
        package com.panchayat.backend.chat.dto;

import com.panchayat.backend.chat.entity.ChatMessage;
import com.panchayat.backend.chat.entity.ChatMessageType;

import java.time.Instant;

public record ChatMessageResponse(
        Long id,
        Long conversationId,
        Long senderUserId,
        ChatMessageType messageType,
        String message,
        String imageUrl,
        String contentType,
        Long imageSizeBytes,
        Instant createdAt,
        // SYSTEM (Sarpanch) messages only
        String title,
        String actionLabel,
        String actionUrl,
        String category,
        Boolean official
) {
    /** A SYSTEM message with its extra fields (title, picture, action, category). */
    public ChatMessageResponse withSystem(java.util.Map<String, Object> x) {
        if (x == null) return this;
        return new ChatMessageResponse(id, conversationId, senderUserId, messageType, message, (String) x.get("imageUrl"), null, null, createdAt,
                (String) x.get("title"), (String) x.get("actionLabel"), (String) x.get("actionUrl"), (String) x.get("category"), true);
    }


    public static ChatMessageResponse from(
            ChatMessage message,
            String decryptedText
    ) {
        boolean isImage = message.getMessageType() == ChatMessageType.IMAGE;

        String imageUrl = isImage
                ? "/api/v1/chat/conversations/"
                + message.getConversationId()
                + "/messages/"
                + message.getId()
                + "/image"
                : null;

        return new ChatMessageResponse(
                message.getId(),
                message.getConversationId(),
                message.getSenderUserId(),
                message.getMessageType(),
                isImage ? null : decryptedText,
                imageUrl,
                isImage ? message.getMediaContentType() : null,
                isImage ? message.getMediaSizeBytes() : null,
                message.getCreatedAt(),
                null, null, null, null, null
        );
    }
}
