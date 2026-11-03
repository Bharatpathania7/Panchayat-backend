package com.panchayat.backend.rewards.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Something a reward rule can give: a gift (into the user's bag) or an effect
 * such as a chat bubble, entry effect or profile effect, or any custom type
 * the admins add. enabled = false keeps it in the rule but it never drops.
 */
@Entity
@Table(name = "reward_rule_items")
public class RewardRuleItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rule_id", nullable = false)
    private RewardRule rule;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(name = "item_type", nullable = false, length = 30)
    private String itemType;

    @Column(name = "gift_id", length = 64)
    private String giftId;

    @Column(nullable = false)
    private int quantity = 1;

    @Column(nullable = false)
    private int priority = 1;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "duration_days")
    private Integer durationDays;

    @Column(name = "media_url", length = 500)
    private String mediaUrl;
    @Column(name = "media_public_id", length = 200)
    private String mediaPublicId;
    @Column(name = "media_format", length = 10)
    private String mediaFormat;

    @Column(name = "sound_url", length = 500)
    private String soundUrl;
    @Column(name = "sound_public_id", length = 200)
    private String soundPublicId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public RewardRule getRule() { return rule; }
    public void setRule(RewardRule rule) { this.rule = rule; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getItemType() { return itemType; }
    public void setItemType(String itemType) { this.itemType = itemType; }
    public String getGiftId() { return giftId; }
    public void setGiftId(String giftId) { this.giftId = giftId; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Integer getDurationDays() { return durationDays; }
    public void setDurationDays(Integer durationDays) { this.durationDays = durationDays; }
    public String getMediaUrl() { return mediaUrl; }
    public void setMediaUrl(String mediaUrl) { this.mediaUrl = mediaUrl; }
    public String getMediaPublicId() { return mediaPublicId; }
    public void setMediaPublicId(String mediaPublicId) { this.mediaPublicId = mediaPublicId; }
    public String getMediaFormat() { return mediaFormat; }
    public void setMediaFormat(String mediaFormat) { this.mediaFormat = mediaFormat; }
    public String getSoundUrl() { return soundUrl; }
    public void setSoundUrl(String soundUrl) { this.soundUrl = soundUrl; }
    public String getSoundPublicId() { return soundPublicId; }
    public void setSoundPublicId(String soundPublicId) { this.soundPublicId = soundPublicId; }
    public Instant getCreatedAt() { return createdAt; }
}
