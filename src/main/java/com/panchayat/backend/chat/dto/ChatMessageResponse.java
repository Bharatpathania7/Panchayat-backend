
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
        Instant createdAt
) {

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
                message.getCreatedAt()
        );
    }
}
