package com.panchayat.backend.tags.entity;

import jakarta.persistence.*;

import java.time.Instant;

/** A BFF tag between two people. */
@Entity
@Table(name = "user_tags")
public class UserTag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The auction winner. */
    @Column(name = "user_a", nullable = false)
    private Long userA;

    /** The person who sat on the auction seat. */
    @Column(name = "user_b", nullable = false)
    private Long userB;

    @Column(name = "pair_low", nullable = false)
    private Long pairLow;

    @Column(name = "pair_high", nullable = false)
    private Long pairHigh;

    @Column(nullable = false, length = 20)
    private String relation;

    @Column(nullable = false, length = 20)
    private String token;

    @Column(nullable = false)
    private long intimacy;

    @Column(name = "room_name", length = 100)
    private String roomName;

    @Column(length = 80)
    private String declaration;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** A pet name the pair chose (shown on the tag), up to 24 characters. */
    @Column(length = 24)
    private String nickname;

    /** Colour theme for the tag card and chip. */
    @Column(length = 20)
    private String theme;

    protected UserTag() {
    }

    public UserTag(Long userA, Long userB, String relation, String token, long intimacy, String roomName, String declaration) {
        this.userA = userA;
        this.userB = userB;
        this.pairLow = Math.min(userA, userB);
        this.pairHigh = Math.max(userA, userB);
        this.relation = relation;
        this.token = token;
        this.intimacy = intimacy;
        this.roomName = roomName;
        this.declaration = declaration;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getUserA() { return userA; }
    public Long getUserB() { return userB; }
    public String getRelation() { return relation; }
    public String getToken() { return token; }
    public long getIntimacy() { return intimacy; }
    public String getRoomName() { return roomName; }
    public String getDeclaration() { return declaration; }
    public Instant getCreatedAt() { return createdAt; }
    public String getNickname() { return nickname; }
    public String getTheme() { return theme; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public void setTheme(String theme) { this.theme = theme; }
    public void setRelation(String relation) { this.relation = relation; }

    public void addIntimacy(long points) {
        this.intimacy = Math.addExact(this.intimacy, Math.max(0, points));
    }

    public boolean involves(Long userId) {
        return userA.equals(userId) || userB.equals(userId);
    }
}
