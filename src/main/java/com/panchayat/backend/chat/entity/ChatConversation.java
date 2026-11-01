
        package com.panchayat.backend.chat.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(
        name = "chat_conversations",
        indexes = {
                @Index(
                        name = "idx_chat_conversations_updated_at",
                        columnList = "updated_at"
                )
        }
)
public class ChatConversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public ChatConversation() {
        // Required by JPA
    }

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
