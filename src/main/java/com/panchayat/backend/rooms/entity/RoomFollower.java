package com.panchayat.backend.rooms.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "voice_room_followers")
public class RoomFollower {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "room_id", nullable = false)
    private Long roomId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RoomFollower() {
    }

    public RoomFollower(Long roomId, Long userId) {
        this.roomId = roomId;
        this.userId = userId;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }

    public Long getRoomId() { return roomId; }
    public Long getUserId() { return userId; }
}
