package com.panchayat.backend.wallet.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "coin_packs")
public class CoinPack {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private Long coins;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal priceInRupees;

    @Column(nullable = false)
    private Long bonusCoins = 0L;

    @Column(nullable = false)
    private Integer bonusPercentage = 0;

    @Column(length = 30)
    private String badge;

    @Column(nullable = false)
    private Boolean active = true;

    @Column(nullable = false)
    private Integer sortOrder = 0;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "image_url", length = 1000)
    private String imageUrl;

    @Column(name = "image_public_id", length = 255)
    private String imagePublicId;

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public String getImagePublicId() {
        return imagePublicId;
    }

    public void setImagePublicId(String imagePublicId) {
        this.imagePublicId = imagePublicId;
    }


    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;

        if (bonusCoins == null) bonusCoins = 0L;
        if (bonusPercentage == null) bonusPercentage = 0;
        if (active == null) active = true;
        if (sortOrder == null) sortOrder = 0;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Long getCoins() {
        return coins;
    }

    public void setCoins(Long coins) {
        this.coins = coins;
    }

    public BigDecimal getPriceInRupees() {
        return priceInRupees;
    }

    public void setPriceInRupees(BigDecimal priceInRupees) {
        this.priceInRupees = priceInRupees;
    }

    public Long getBonusCoins() {
        return bonusCoins;
    }

    public void setBonusCoins(Long bonusCoins) {
        this.bonusCoins = bonusCoins;
    }

    public Integer getBonusPercentage() {
        return bonusPercentage;
    }

    public void setBonusPercentage(Integer bonusPercentage) {
        this.bonusPercentage = bonusPercentage;
    }

    public String getBadge() {
        return badge;
    }

    public void setBadge(String badge) {
        this.badge = badge;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}