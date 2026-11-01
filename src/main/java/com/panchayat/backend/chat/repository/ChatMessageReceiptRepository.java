package com.panchayat.backend.chat.repository;

import com.panchayat.backend.chat.entity.ChatMessageReceipt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ChatMessageReceiptRepository
        extends JpaRepository<ChatMessageReceipt, Long> {

    List<ChatMessageReceipt> findByUserIdAndReadAtIsNull(Long userId);

    List<ChatMessageReceipt> findByMessageId(Long messageId);

    long countByUserIdAndReadAtIsNull(Long userId);

    List<ChatMessageReceipt> findByUserIdAndMessageIdInAndReadAtIsNull(
            Long userId,
            List<Long> messageIds
    );

    @Query("""
        SELECT COUNT(r)
        FROM ChatMessageReceipt r, ChatMessage m
        WHERE r.messageId = m.id
          AND r.userId = :userId
          AND r.readAt IS NULL
          AND m.conversationId = :conversationId
          AND m.deletedAt IS NULL
          AND m.senderUserId <> :userId
        """)
    long countUnreadMessagesInConversation(
            @Param("userId") Long userId,
            @Param("conversationId") Long conversationId
    );
}