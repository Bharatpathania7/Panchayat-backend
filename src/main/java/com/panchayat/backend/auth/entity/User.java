
        package com.panchayat.backend.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private Role role = Role.USER;

    @Column(name = "full_name", length = 100)
    private String fullName;

    @Column(length = 20)
    private String gender;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified = false;

    @Column(name = "profile_completed", nullable = false)
    private boolean profileCompleted = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(nullable = false)
    private int level = 0;

    @Column(name = "exp_points", nullable = false)
    private long expPoints = 0;

    @Column(name = "charm_points", nullable = false)
    private long charmPoints = 0;

    @Column(name = "vip_points", nullable = false)
    private long vipPoints = 0;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "user_favorite_games",
            joinColumns = @JoinColumn(name = "user_id")
    )
    @Column(name = "game", nullable = false, length = 30)
    @OrderColumn(name = "game_order")
    private List<String> favoriteGames = new ArrayList<>();

    @Column(name = "avatar_url", length = 1000)
    private String avatarUrl;

    @Column(name = "banner_url", length = 1000)
    private String bannerUrl;

    @Column(name = "name_font", length = 30)
    private String nameFont = "CLASSIC";

    @Column(length = 120)
    private String bio;

    @Column(name = "avatar_background", length = 30)
    private String avatarBackground;

    @Column(name = "avatar_skin_tone", length = 30)
    private String avatarSkinTone;

    @Column(name = "avatar_outfit", length = 30)
    private String avatarOutfit;

    @Column(name = "avatar_hair", length = 30)
    private String avatarHair;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;

        if (role == null) {
            role = Role.USER;
        }

        if (favoriteGames == null) {
            favoriteGames = new ArrayList<>();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getGender() {
        return gender;
    }

    public void setGender(String gender) {
        this.gender = gender;
    }

    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    public void setDateOfBirth(LocalDate dateOfBirth) {
        this.dateOfBirth = dateOfBirth;
    }

    public boolean isEmailVerified() {
        return emailVerified;
    }

    public void setEmailVerified(boolean emailVerified) {
        this.emailVerified = emailVerified;
    }

    public boolean isProfileCompleted() {
        return profileCompleted;
    }

    public void setProfileCompleted(boolean profileCompleted) {
        this.profileCompleted = profileCompleted;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public int getLevel() {
        return level;
    }

    public void setLevel(int level) {
        this.level = level;
    }

    public long getExpPoints() {
        return expPoints;
    }

    public void setExpPoints(long expPoints) {
        this.expPoints = expPoints;
    }

    public long getCharmPoints() {
        return charmPoints;
    }

    public void setCharmPoints(long charmPoints) {
        this.charmPoints = charmPoints;
    }

    public long getVipPoints() {
        return vipPoints;
    }

    public void setVipPoints(long vipPoints) {
        this.vipPoints = vipPoints;
    }

    public List<String> getFavoriteGames() {
        return favoriteGames;
    }

    public void setFavoriteGames(List<String> favoriteGames) {
        this.favoriteGames = favoriteGames == null
                ? new ArrayList<>()
                : new ArrayList<>(favoriteGames);
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    public String getBannerUrl() {
        return bannerUrl;
    }

    public void setBannerUrl(String bannerUrl) {
        this.bannerUrl = bannerUrl;
    }

    public String getNameFont() {
        return nameFont;
    }

    public void setNameFont(String nameFont) {
        this.nameFont = nameFont;
    }

    public String getBio() {
        return bio;
    }

    public void setBio(String bio) {
        this.bio = bio;
    }

    public String getAvatarBackground() {
        return avatarBackground;
    }

    public void setAvatarBackground(String avatarBackground) {
        this.avatarBackground = avatarBackground;
    }

    public String getAvatarSkinTone() {
        return avatarSkinTone;
    }

    public void setAvatarSkinTone(String avatarSkinTone) {
        this.avatarSkinTone = avatarSkinTone;
    }

    public String getAvatarOutfit() {
        return avatarOutfit;
    }

    public void setAvatarOutfit(String avatarOutfit) {
        this.avatarOutfit = avatarOutfit;
    }

    public String getAvatarHair() {
        return avatarHair;
    }

    public void setAvatarHair(String avatarHair) {
        this.avatarHair = avatarHair;
    }
}
