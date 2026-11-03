package com.panchayat.backend.gifts.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "room_gift_events")
public class RoomGiftEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "room_id", nullable = false)
    private Long roomId;

    @Column(name = "sender_id", nullable = false)
    private Long senderId;

    @Column(name = "receiver_id", nullable = false)
    private Long receiverId;

    @Column(name = "gift_id", nullable = false, length = 40)
    private String giftId;

    @Column(name = "gift_count", nullable = false)
    private int giftCount;

    @Column(nullable = false)
    private long coins;

    @Column(name = "receiver_earned", nullable = false)
    private long receiverEarned;

    @Column(name = "request_id", nullable = false, length = 64, unique = true)
    private String requestId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RoomGiftEvent() {
    }

    public RoomGiftEvent(Long roomId, Long senderId, Long receiverId, String giftId, int giftCount,
                         long coins, long receiverEarned, String requestId) {
        this.roomId = roomId;
        this.senderId = senderId;
        this.receiverId = receiverId;
        this.giftId = giftId;
        this.giftCount = giftCount;
        this.coins = coins;
        this.receiverEarned = receiverEarned;
        this.requestId = requestId;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getRoomId() { return roomId; }
    public Long getSenderId() { return senderId; }
    public Long getReceiverId() { return receiverId; }
    public String getGiftId() { return giftId; }
    public int getGiftCount() { return giftCount; }
    public long getCoins() { return coins; }
    public long getReceiverEarned() { return receiverEarned; }
    public Instant getCreatedAt() { return createdAt; }
}
