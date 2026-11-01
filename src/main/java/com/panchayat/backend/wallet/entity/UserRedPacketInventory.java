
        package com.panchayat.backend.wallet.entity;

import com.panchayat.backend.auth.entity.User;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(
        name = "user_red_packet_inventory",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_user_red_packet",
                        columnNames = {"user_id", "red_packet_product_id"}
                )
        }
)
public class UserRedPacketInventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "red_packet_product_id", nullable = false)
    private RedPacketProduct redPacketProduct;

    @Column(nullable = false)
    private long quantity = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

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

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public RedPacketProduct getRedPacketProduct() {
        return redPacketProduct;
    }

    public void setRedPacketProduct(RedPacketProduct redPacketProduct) {
        this.redPacketProduct = redPacketProduct;
    }

    public long getQuantity() {
        return quantity;
    }

    public void setQuantity(long quantity) {
        if (quantity < 0) {
            throw new IllegalArgumentException("Quantity cannot be negative");
        }
        this.quantity = quantity;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
