package com.panchayat.backend.rooms.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "voice_room_admins")
public class RoomAdmin {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "room_id", nullable = false)
    private Long roomId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RoomAdmin() {
    }

    public RoomAdmin(Long roomId, Long userId, Long createdBy) {
        this.roomId = roomId;
        this.userId = userId;
        this.createdBy = createdBy;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }

    public Long getRoomId() { return roomId; }
    public Long getUserId() { return userId; }
}
