package com.panchayat.backend.gifts.entity;

import jakarta.persistence.*;

import java.time.Instant;

/** A gift on sale, managed from the admin panel (table gifts). */
@Entity
@Table(name = "gifts")
public class GiftDefinition {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 60)
    private String name;

    @Column(nullable = false)
    private long price;

    @Column(nullable = false, length = 30)
    private String category = "Popular";

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "image_url", length = 500)
    private String imageUrl;
    @Column(name = "image_public_id", length = 200)
    private String imagePublicId;

    @Column(name = "animation_url", length = 500)
    private String animationUrl;
    @Column(name = "animation_public_id", length = 200)
    private String animationPublicId;
    @Column(name = "animation_format", length = 10)
    private String animationFormat;
    @Column(name = "animation_ratio", length = 20)
    private String animationRatio;

    @Column(name = "sound_url", length = 500)
    private String soundUrl;
    @Column(name = "sound_public_id", length = 200)
    private String soundPublicId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public long getPrice() { return price; }
    public void setPrice(long price) { this.price = price; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    public String getImagePublicId() { return imagePublicId; }
    public void setImagePublicId(String imagePublicId) { this.imagePublicId = imagePublicId; }
    public String getAnimationRatio() { return animationRatio; }
    public void setAnimationRatio(String animationRatio) { this.animationRatio = animationRatio; }
    public String getAnimationUrl() { return animationUrl; }
    public void setAnimationUrl(String animationUrl) { this.animationUrl = animationUrl; }
    public String getAnimationPublicId() { return animationPublicId; }
    public void setAnimationPublicId(String animationPublicId) { this.animationPublicId = animationPublicId; }
    public String getAnimationFormat() { return animationFormat; }
    public void setAnimationFormat(String animationFormat) { this.animationFormat = animationFormat; }
    public String getSoundUrl() { return soundUrl; }
    public void setSoundUrl(String soundUrl) { this.soundUrl = soundUrl; }
    public String getSoundPublicId() { return soundPublicId; }
    public void setSoundPublicId(String soundPublicId) { this.soundPublicId = soundPublicId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
