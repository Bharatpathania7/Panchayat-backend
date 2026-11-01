package com.panchayat.backend.payment.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(
        name = "payment_transactions",
        indexes = {
                @Index(
                        name = "idx_payment_transactions_user_created",
                        columnList = "user_id, created_at"
                ),
                @Index(
                        name = "idx_payment_transactions_status_created",
                        columnList = "status, created_at"
                )
        }
)
public class PaymentTransaction {

    public enum Purpose {
        COIN_PACK,
        RED_PACKET,
        VIP
    }

    public enum Status {
        PENDING,
        SUCCESS,
        FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "txn_id", nullable = false, unique = true, length = 100)
    private String txnId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Purpose purpose;

    @Column(name = "product_id")
    private Long productId;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency = "INR";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(name = "payu_payment_id", length = 100)
    private String payuPaymentId;

    @Column(name = "payment_mode", length = 50)
    private String paymentMode;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "purchased_coins")
    private Long purchasedCoins;

    @Column(name = "purchased_bonus_coins")
    private Long purchasedBonusCoins;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();

        createdAt = now;
        updatedAt = now;

        if (currency == null) {
            currency = "INR";
        }

        if (status == null) {
            status = Status.PENDING;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getTxnId() {
        return txnId;
    }

    public void setTxnId(String txnId) {
        this.txnId = txnId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Purpose getPurpose() {
        return purpose;
    }

    public void setPurpose(Purpose purpose) {
        this.purpose = purpose;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getPayuPaymentId() {
        return payuPaymentId;
    }

    public void setPayuPaymentId(String payuPaymentId) {
        this.payuPaymentId = payuPaymentId;
    }

    public String getPaymentMode() {
        return paymentMode;
    }

    public void setPaymentMode(String paymentMode) {
        this.paymentMode = paymentMode;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }

    public void setVerifiedAt(Instant verifiedAt) {
        this.verifiedAt = verifiedAt;
    }
    public Long getPurchasedCoins() {
        return purchasedCoins;
    }

    public void setPurchasedCoins(Long purchasedCoins) {
        this.purchasedCoins = purchasedCoins;
    }

    public Long getPurchasedBonusCoins() {
        return purchasedBonusCoins;
    }

    public void setPurchasedBonusCoins(Long purchasedBonusCoins) {
        this.purchasedBonusCoins = purchasedBonusCoins;
    }
}