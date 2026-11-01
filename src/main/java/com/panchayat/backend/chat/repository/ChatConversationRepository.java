
        package com.panchayat.backend.chat.repository;

import com.panchayat.backend.chat.entity.ChatConversation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatConversationRepository
        extends JpaRepository<ChatConversation, Long> {
}
