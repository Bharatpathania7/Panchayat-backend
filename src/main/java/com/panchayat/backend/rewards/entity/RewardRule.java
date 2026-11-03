package com.panchayat.backend.rewards.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A spend reward. RANDOM: every coinThreshold coins a user spends, they win one
 * enabled item at random (priority 1 most likely, 4 least). GUARANTEED: after
 * coinThreshold coins they get every enabled item. repeatable: again every
 * coinThreshold coins, otherwise once per user.
 */
@Entity
@Table(name = "reward_rules")
public class RewardRule {

    public enum Kind { RANDOM, GUARANTEED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 80)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Kind kind;

    @Column(name = "coin_threshold", nullable = false)
    private long coinThreshold;

    @Column(nullable = false)
    private boolean repeatable = true;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_by", length = 60)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "rule", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("priority ASC, id ASC")
    private List<RewardRuleItem> items = new ArrayList<>();

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Kind getKind() { return kind; }
    public void setKind(Kind kind) { this.kind = kind; }
    public long getCoinThreshold() { return coinThreshold; }
    public void setCoinThreshold(long coinThreshold) { this.coinThreshold = coinThreshold; }
    public boolean isRepeatable() { return repeatable; }
    public void setRepeatable(boolean repeatable) { this.repeatable = repeatable; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<RewardRuleItem> getItems() { return items; }
}
