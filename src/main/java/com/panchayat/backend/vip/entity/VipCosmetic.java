package com.panchayat.backend.vip.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(
        name = "vip_cosmetics",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_vip_cosmetic_level_type_format",
                        columnNames = {"vip_level", "cosmetic_type", "format"}
                )
        },
        indexes = {
                @Index(name = "idx_vip_cosmetics_level", columnList = "vip_level")
        }
)
public class VipCosmetic {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "vip_level", nullable = false)
    private Integer vipLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "cosmetic_type", nullable = false, length = 20)
    private CosmeticType cosmeticType;

    @Enumerated(EnumType.STRING)
    @Column(name = "format", nullable = false, length = 10)
    private CosmeticFormat format;

    @Column(name = "public_id", nullable = false, unique = true)
    private String publicId;

    @Column(name = "secure_url", nullable = false, columnDefinition = "TEXT")
    private String secureUrl;

    @Column(name = "original_filename")
    private String originalFilename;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public enum CosmeticType {
        FRAME, BUBBLE, ENTRY, BADGE
    }

    public enum CosmeticFormat {
        SVGA, PNG
    }

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

    public Integer getVipLevel() {
        return vipLevel;
    }

    public void setVipLevel(Integer vipLevel) {
        this.vipLevel = vipLevel;
    }

    public CosmeticType getCosmeticType() {
        return cosmeticType;
    }

    public void setCosmeticType(CosmeticType cosmeticType) {
        this.cosmeticType = cosmeticType;
    }

    public CosmeticFormat getFormat() {
        return format;
    }

    public void setFormat(CosmeticFormat format) {
        this.format = format;
    }

    public String getPublicId() {
        return publicId;
    }

    public void setPublicId(String publicId) {
        this.publicId = publicId;
    }

    public String getSecureUrl() {
        return secureUrl;
    }

    public void setSecureUrl(String secureUrl) {
        this.secureUrl = secureUrl;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public void setOriginalFilename(String originalFilename) {
        this.originalFilename = originalFilename;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}