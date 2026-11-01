
        package com.panchayat.backend.chat.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(
        name = "chat_conversation_participants",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_chat_conversation_user",
                        columnNames = {"conversation_id", "user_id"}
                )
        },
        indexes = {
                @Index(
                        name = "idx_chat_participant_user",
                        columnList = "user_id"
                ),
                @Index(
                        name = "idx_chat_participant_conversation",
                        columnList = "conversation_id"
                )
        }
)
public class ChatConversationParticipant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;

    @Column(name = "last_read_message_id")
    private Long lastReadMessageId;

    public ChatConversationParticipant() {
        // Required by JPA
    }

    @PrePersist
    protected void onCreate() {
        if (joinedAt == null) {
            joinedAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public Long getConversationId() {
        return conversationId;
    }

    public void setConversationId(Long conversationId) {
        this.conversationId = conversationId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }

    public Long getLastReadMessageId() {
        return lastReadMessageId;
    }

    public void setLastReadMessageId(Long lastReadMessageId) {
        this.lastReadMessageId = lastReadMessageId;
    }
}
