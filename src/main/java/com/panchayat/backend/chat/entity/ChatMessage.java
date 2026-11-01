
        package com.panchayat.backend.chat.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(
        name = "chat_messages",
        indexes = {
                @Index(
                        name = "idx_chat_messages_conversation_created",
                        columnList = "conversation_id, created_at, id"
                )
        }
)
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Column(name = "sender_user_id", nullable = false)
    private Long senderUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "message_type", nullable = false, length = 10)
    private ChatMessageType messageType = ChatMessageType.TEXT;

    // Encrypted message text or optional image caption.
    @Column(name = "ciphertext", columnDefinition = "TEXT")
    private String ciphertext;

    // Base64-encoded AES-GCM nonce.
    @Column(name = "nonce", length = 32)
    private String nonce;

    @Column(name = "encryption_key_version", nullable = false)
    private Integer encryptionKeyVersion = 1;

    // Opaque reference to the privately stored image.
    @Column(name = "media_storage_key", length = 500)
    private String mediaStorageKey;

    @Column(name = "media_content_type", length = 100)
    private String mediaContentType;

    @Column(name = "media_size_bytes")
    private Long mediaSizeBytes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "edited_at")
    private Instant editedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    public ChatMessage() {
        // Required by JPA
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
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

    public Long getSenderUserId() {
        return senderUserId;
    }

    public void setSenderUserId(Long senderUserId) {
        this.senderUserId = senderUserId;
    }

    public ChatMessageType getMessageType() {
        return messageType;
    }

    public void setMessageType(ChatMessageType messageType) {
        this.messageType = messageType;
    }

    public String getCiphertext() {
        return ciphertext;
    }

    public void setCiphertext(String ciphertext) {
        this.ciphertext = ciphertext;
    }

    public String getNonce() {
        return nonce;
    }

    public void setNonce(String nonce) {
        this.nonce = nonce;
    }

    public Integer getEncryptionKeyVersion() {
        return encryptionKeyVersion;
    }

    public void setEncryptionKeyVersion(Integer encryptionKeyVersion) {
        this.encryptionKeyVersion = encryptionKeyVersion;
    }

    public String getMediaStorageKey() {
        return mediaStorageKey;
    }

    public void setMediaStorageKey(String mediaStorageKey) {
        this.mediaStorageKey = mediaStorageKey;
    }

    public String getMediaContentType() {
        return mediaContentType;
    }

    public void setMediaContentType(String mediaContentType) {
        this.mediaContentType = mediaContentType;
    }

    public Long getMediaSizeBytes() {
        return mediaSizeBytes;
    }

    public void setMediaSizeBytes(Long mediaSizeBytes) {
        this.mediaSizeBytes = mediaSizeBytes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getEditedAt() {
        return editedAt;
    }

    public void setEditedAt(Instant editedAt) {
        this.editedAt = editedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }
}
