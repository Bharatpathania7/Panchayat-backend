
        package com.panchayat.backend.chat.repository;

import com.panchayat.backend.chat.entity.ChatConversationParticipant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChatConversationParticipantRepository
        extends JpaRepository<ChatConversationParticipant, Long> {

    Optional<ChatConversationParticipant> findByConversationIdAndUserId(
            Long conversationId,
            Long userId
    );

    List<ChatConversationParticipant> findByUserId(Long userId);

    List<ChatConversationParticipant> findByConversationId(Long conversationId);

    boolean existsByConversationIdAndUserId(
            Long conversationId,
            Long userId
    );
}
