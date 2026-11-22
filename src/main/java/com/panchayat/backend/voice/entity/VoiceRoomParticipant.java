package com.panchayat.backend.voice.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "voice_room_participants")
public class VoiceRoomParticipant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "room_id", nullable = false)
    private Long roomId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 20)
    private String role = "MEMBER";

    @Column(nullable = false, length = 20)
    private String status = "JOINED";

    @Column(name = "is_muted", nullable = false)
    private boolean muted = false;

    /** A silent-observer staff account that joined a password room without the password: hidden
     *  from everyone else in the room (not in the participant list, no join/leave broadcast). */
    @Column(name = "invisible", nullable = false)
    private boolean invisible = false;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    @Column(name = "left_at")
    private Instant leftAt;

    @PrePersist
    protected void onCreate() {
        if (joinedAt == null) {
            joinedAt = Instant.now();
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

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public boolean isMuted() {
        return muted;
    }

    public void setMuted(boolean muted) {
        this.muted = muted;
    }

    public boolean isInvisible() {
        return invisible;
    }

    public void setInvisible(boolean invisible) {
        this.invisible = invisible;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }
    public void setJoinedAt(Instant joinedAt) {
        this.joinedAt = joinedAt;
    }
    public Instant getLeftAt() {
        return leftAt;
    }

    public void setLeftAt(Instant leftAt) {
        this.leftAt = leftAt;
    }
}