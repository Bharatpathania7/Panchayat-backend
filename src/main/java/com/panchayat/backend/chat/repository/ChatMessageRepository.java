package com.panchayat.backend.chat.repository;

import com.panchayat.backend.chat.entity.ChatMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    Page<ChatMessage> findByConversationIdAndDeletedAtIsNull(
            Long conversationId,
            Pageable pageable
    );

    Page<ChatMessage> findByConversationIdAndIdLessThanAndDeletedAtIsNull(
            Long conversationId,
            Long beforeMessageId,
            Pageable pageable
    );

    Optional<ChatMessage> findByIdAndConversationId(
            Long messageId,
            Long conversationId
    );

    Optional<ChatMessage>
    findFirstByConversationIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(
            Long conversationId
    );
}