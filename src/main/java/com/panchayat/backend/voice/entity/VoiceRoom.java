
        package com.panchayat.backend.voice.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(
        name = "voice_rooms",
        indexes = {
                @Index(
                        name = "idx_voice_rooms_status_created",
                        columnList = "status, created_at"
                ),
                @Index(
                        name = "idx_voice_rooms_owner",
                        columnList = "owner_id"
                )
        }
)
public class VoiceRoom {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "room_type", nullable = false, length = 20)
    private VoiceRoomType roomType = VoiceRoomType.VOICE;

    @Column(name = "is_private", nullable = false)
    private boolean isPrivate = false;

    @Column(name = "max_participants", nullable = false)
    private int maxParticipants = 8;

    @Column(nullable = false, length = 20)
    private String status = "ACTIVE";

    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

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

    public Long getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(Long ownerId) {
        this.ownerId = ownerId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public VoiceRoomType getRoomType() {
        return roomType;
    }

    public void setRoomType(VoiceRoomType roomType) {
        this.roomType = roomType;
    }

    public boolean isPrivate() {
        return isPrivate;
    }

    public void setPrivate(boolean isPrivate) {
        this.isPrivate = isPrivate;
    }

    public int getMaxParticipants() {
        return maxParticipants;
    }

    public void setMaxParticipants(int maxParticipants) {
        this.maxParticipants = maxParticipants;
    }

    public String getStatus() {
        return status;
    }

    // Added for closing a voice room.
    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
