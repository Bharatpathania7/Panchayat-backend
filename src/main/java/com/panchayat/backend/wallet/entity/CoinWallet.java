package com.panchayat.backend.wallet.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(
        name = "coin_wallets",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_coin_wallets_user_id",
                        columnNames = "user_id"
                )
        }
)
public class CoinWallet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(nullable = false)
    private long balance = 0L;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CoinWallet() {
    }

    public CoinWallet(Long userId) {
        this.userId = userId;
        this.balance = 0L;
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

    public Long getUserId() {
        return userId;
    }

    public long getBalance() {
        return balance;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setBalance(long balance) {
        if (balance < 0) {
            throw new IllegalArgumentException(
                    "Wallet balance cannot be negative"
            );
        }

        this.balance = balance;
    }
}