
        package com.panchayat.backend.voice.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(
        name = "voice_room_chat_messages",
        indexes = {
                @Index(
                        name = "idx_voice_room_chat_messages_history",
                        columnList = "room_id, created_at, id"
                ),
                @Index(
                        name = "idx_voice_room_chat_messages_sender",
                        columnList = "sender_user_id, created_at"
                )
        }
)
public class VoiceRoomChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "room_id", nullable = false)
    private Long roomId;

    @Column(name = "sender_user_id", nullable = false)
    private Long senderUserId;

    @Column(name = "ciphertext", nullable = false, columnDefinition = "TEXT")
    private String ciphertext;

    @Column(name = "nonce", nullable = false, length = 64)
    private String nonce;

    @Column(name = "encryption_key_version", nullable = false)
    private Integer encryptionKeyVersion = 1;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "edited_at")
    private Instant editedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    public VoiceRoomChatMessage() {}

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public Long getRoomId() {
        return roomId;
    }

    public void setRoomId(Long roomId) {
        this.roomId = roomId;
    }

    public Long getSenderUserId() {
        return senderUserId;
    }

    public void setSenderUserId(Long senderUserId) {
        this.senderUserId = senderUserId;
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
