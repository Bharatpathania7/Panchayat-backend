package com.panchayat.backend.wallet.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(
        name = "coin_transactions",
        indexes = {
                @Index(
                        name = "idx_coin_transactions_user_created",
                        columnList = "user_id, created_at"
                ),
                @Index(
                        name = "idx_coin_transactions_reason",
                        columnList = "reason"
                )
        }
)
public class CoinTransaction {

    public enum TransactionType {
        CREDIT,
        DEBIT
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 10)
    private TransactionType transactionType;

    @Column(name = "idempotency_key", length = 100)
    private String idempotencyKey;

    @Column(nullable = false)
    private long amount;

    @Column(name = "balance_before", nullable = false)
    private long balanceBefore;

    @Column(name = "balance_after", nullable = false)
    private long balanceAfter;

    @Column(nullable = false, length = 40)
    private String reason;

    @Column(name = "reference_id", length = 100)
    private String referenceId;

    @Column(length = 500)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CoinTransaction() {
    }

    public CoinTransaction(
            Long userId,
            TransactionType transactionType,
            long amount,
            long balanceBefore,
            long balanceAfter,
            String reason,
            String referenceId,
            String idempotencyKey,
            String description) {

        this.userId = userId;
        this.transactionType = transactionType;
        this.amount = amount;
        this.balanceBefore = balanceBefore;
        this.balanceAfter = balanceAfter;
        this.reason = reason;
        this.referenceId = referenceId;
        this.idempotencyKey = idempotencyKey;
        this.description = description;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public TransactionType getTransactionType() {
        return transactionType;
    }

    public long getAmount() {
        return amount;
    }

    public long getBalanceBefore() {
        return balanceBefore;
    }

    public long getBalanceAfter() {
        return balanceAfter;
    }

    public String getReason() {
        return reason;
    }

    public String getReferenceId() {
        return referenceId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getDescription() {
        return description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}