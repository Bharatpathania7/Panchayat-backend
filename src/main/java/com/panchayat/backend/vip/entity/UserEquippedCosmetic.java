
        package com.panchayat.backend.vip.entity;

import com.panchayat.backend.auth.entity.User;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(
        name = "user_equipped_cosmetics",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_user_equipped_cosmetic_type",
                        columnNames = {"user_id", "cosmetic_type"}
                )
        },
        indexes = {
                @Index(
                        name = "idx_user_equipped_cosmetics_user",
                        columnList = "user_id"
                )
        }
)
public class UserEquippedCosmetic {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cosmetic_id", nullable = false)
    private VipCosmetic cosmetic;

    @Enumerated(EnumType.STRING)
    @Column(name = "cosmetic_type", nullable = false, length = 20)
    private VipCosmetic.CosmeticType cosmeticType;

    @Column(name = "equipped_at", nullable = false)
    private Instant equippedAt;

    @PrePersist
    protected void onCreate() {
        if (equippedAt == null) {
            equippedAt = Instant.now();
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

    public VipCosmetic.CosmeticType getCosmeticType() {
        return cosmeticType;
    }

    public void setCosmeticType(
            VipCosmetic.CosmeticType cosmeticType
    ) {
        this.cosmeticType = cosmeticType;
    }

    public Instant getEquippedAt() {
        return equippedAt;
    }
}
