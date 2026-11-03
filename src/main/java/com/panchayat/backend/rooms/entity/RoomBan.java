package com.panchayat.backend.rooms.entity;

import jakarta.persistence.*;
import java.time.Instant;

/** Blocked from a room. expiresAt null = until unbanned; a kick sets a short expiry. */
@Entity
@Table(name = "voice_room_bans")
public class RoomBan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "room_id", nullable = false)
    private Long roomId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "banned_by", nullable = false)
    private Long bannedBy;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RoomBan() {
    }

    public RoomBan(Long roomId, Long userId) {
        this.roomId = roomId;
        this.userId = userId;
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public boolean isActive(Instant now) {
        return expiresAt == null || expiresAt.isAfter(now);
    }

    public Long getRoomId() { return roomId; }
    public Long getUserId() { return userId; }
    public Long getBannedBy() { return bannedBy; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCreatedAt() { return createdAt; }

    public void renew(Long bannedBy, Instant expiresAt) {
        this.bannedBy = bannedBy;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
    }
}
