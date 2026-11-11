
        package com.panchayat.backend.voice.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(
        name = "voice_rooms",
        indexes = {
                @Index(
                        name = "idx_voice_rooms_status_created",
                        columnList = "status, created_at"
                ),
                @Index(
                        name = "idx_voice_rooms_owner",
                        columnList = "owner_id"
                )
        }
)
public class VoiceRoom {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "room_type", nullable = false, length = 20)
    private VoiceRoomType roomType = VoiceRoomType.VOICE;

    @Column(name = "is_private", nullable = false)
    private boolean isPrivate = false;

    @Column(name = "max_participants", nullable = false)
    private int maxParticipants = 8;

    @Column(nullable = false, length = 20)
    private String status = "ACTIVE";

    /** Mic order: people ask the owner or an admin before taking a seat. */
    @Column(name = "mic_order", nullable = false)
    private boolean micOrder = false;

    /** Closed seat indexes, comma separated (e.g. "3,5"). */
    @Column(name = "closed_seats", nullable = false, length = 100)
    private String closedSeats = "";

    /** A Newbie Welcome Room (managed in Admin > Newbie rooms). */
    @Column(nullable = false)
    private boolean newbie = false;

    /** The host has opened the 6 extra welcome seats. */
    @Column(name = "extra_seats", nullable = false)
    private boolean extraSeats = false;

    @Column(length = 30)
    private String category;

    @Column(name = "cover_theme", length = 20)
    private String coverTheme;

    @Column(name = "cover_motif", length = 20)
    private String coverMotif;

    @Column(name = "cover_image_url", length = 1000)
    private String coverImageUrl;

    @Column(name = "cover_image_public_id", length = 300)
    private String coverImagePublicId;

    @Column(length = 500)
    private String announcement;

    /** 4-digit password, or null. Never sent to people who aren't owner or admin. */
    @Column(length = 4)
    private String password;

    /** JSON object of display and chat settings. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String settings = "{}";

    @Column(name = "member_count", nullable = false)
    private int memberCount = 0;

    @Column(name = "fan_count", nullable = false)
    private int fanCount = 0;

    /** Room points from gifts (100 per coin); the room level comes from these. */
    @Column(nullable = false)
    private long points = 0;

    public long getPoints() {
        return points;
    }

    /** Room EXP (sets the room level). Never written from here: RoomExpService adds to it in the database. */
    @Column(nullable = false, updatable = false, insertable = false)
    private long exp = 0;

    public long getExp() {
        return exp;
    }

    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
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

    public Long getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(Long ownerId) {
        this.ownerId = ownerId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public VoiceRoomType getRoomType() {
        return roomType;
    }

    public void setRoomType(VoiceRoomType roomType) {
        this.roomType = roomType;
    }

    public boolean isPrivate() {
        return isPrivate;
    }

    public void setPrivate(boolean isPrivate) {
        this.isPrivate = isPrivate;
    }

    public int getMaxParticipants() {
        return maxParticipants;
    }

    public void setMaxParticipants(int maxParticipants) {
        this.maxParticipants = maxParticipants;
    }

    public String getStatus() {
        return status;
    }

    // Added for closing a voice room.
    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public boolean isMicOrder() {
        return micOrder;
    }

    public void setMicOrder(boolean micOrder) {
        this.micOrder = micOrder;
    }

    /** The background picture of a Newbie Welcome Room (the admin's, or the one its host uploaded). */
    @Column(name = "newbie_background", length = 500)
    private String newbieBackground;

    public String getNewbieBackground() {
        return newbieBackground;
    }

    public void setNewbieBackground(String newbieBackground) {
        this.newbieBackground = newbieBackground;
    }

    public boolean isNewbie() {
        return newbie;
    }

    public void setNewbie(boolean newbie) {
        this.newbie = newbie;
    }

    public boolean isExtraSeats() {
        return extraSeats;
    }

    public void setExtraSeats(boolean extraSeats) {
        this.extraSeats = extraSeats;
    }

    public String getClosedSeats() {
        return closedSeats == null ? "" : closedSeats;
    }

    public void setClosedSeats(String closedSeats) {
        this.closedSeats = closedSeats == null ? "" : closedSeats;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getCoverTheme() {
        return coverTheme;
    }

    public void setCoverTheme(String coverTheme) {
        this.coverTheme = coverTheme;
    }

    public String getCoverMotif() {
        return coverMotif;
    }

    public void setCoverMotif(String coverMotif) {
        this.coverMotif = coverMotif;
    }

    public String getCoverImageUrl() {
        return coverImageUrl;
    }

    public void setCoverImageUrl(String coverImageUrl) {
        this.coverImageUrl = coverImageUrl;
    }

    public String getCoverImagePublicId() {
        return coverImagePublicId;
    }

    public void setCoverImagePublicId(String coverImagePublicId) {
        this.coverImagePublicId = coverImagePublicId;
    }

    public String getAnnouncement() {
        return announcement;
    }

    public void setAnnouncement(String announcement) {
        this.announcement = announcement;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getSettings() {
        return settings;
    }

    public void setSettings(String settings) {
        this.settings = settings;
    }

    public int getMemberCount() {
        return memberCount;
    }

    public void setMemberCount(int memberCount) {
        this.memberCount = memberCount;
    }

    public int getFanCount() {
        return fanCount;
    }

    public void setFanCount(int fanCount) {
        this.fanCount = fanCount;
    }
}
