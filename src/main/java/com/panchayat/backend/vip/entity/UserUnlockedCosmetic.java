
        package com.panchayat.backend.vip.entity;

import com.panchayat.backend.auth.entity.User;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(
        name = "user_unlocked_cosmetics",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_user_unlocked_cosmetic",
                        columnNames = {"user_id", "cosmetic_id"}
                )
        },
        indexes = {
                @Index(
                        name = "idx_unlocked_cosmetics_user",
                        columnList = "user_id"
                )
        }
)
public class UserUnlockedCosmetic {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cosmetic_id", nullable = false)
    private VipCosmetic cosmetic;

    @Column(name = "unlocked_at", nullable = false, updatable = false)
    private Instant unlockedAt;

    @PrePersist
    protected void onCreate() {
        if (unlockedAt == null) {
            unlockedAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public VipCosmetic getCosmetic() {
        return cosmetic;
    }

    public void setCosmetic(VipCosmetic cosmetic) {
        this.cosmetic = cosmetic;
    }

    public Instant getUnlockedAt() {
        return unlockedAt;
    }
}
