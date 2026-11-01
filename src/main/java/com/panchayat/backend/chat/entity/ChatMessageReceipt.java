
        package com.panchayat.backend.chat.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(
        name = "chat_message_receipts",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_chat_message_receipt_user",
                        columnNames = {"message_id", "user_id"}
                )
        },
        indexes = {
                @Index(
                        name = "idx_chat_receipts_user_unread",
                        columnList = "user_id, read_at"
                )
        }
)
public class ChatMessageReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_id", nullable = false)
    private Long messageId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "read_at")
    private Instant readAt;

    public ChatMessageReceipt() {
    }

    public Long getId() {
        return id;
    }

    public Long getMessageId() {
        return messageId;
    }

    public void setMessageId(Long messageId) {
        this.messageId = messageId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    public void setDeliveredAt(Instant deliveredAt) {
        this.deliveredAt = deliveredAt;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public void setReadAt(Instant readAt) {
        this.readAt = readAt;
    }
}