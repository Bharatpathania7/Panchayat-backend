
        package com.panchayat.backend.admin.shop.dto.response;

import com.panchayat.backend.admin.shop.entity.ShopProduct.AssetType;
import com.panchayat.backend.admin.shop.entity.ShopProduct.Rarity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public class ProductResponse {

    private Long id;
    private Long categoryId;
    private String categoryName;
    private String name;
    private String description;
    private BigDecimal price;
    private Rarity rarity;
    private String assetUrl;
    private AssetType assetType;
    private Integer displayOrder;
    private Boolean active;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public ProductResponse(
            Long id,
            Long categoryId,
            String categoryName,
            String name,
            String description,
            BigDecimal price,
            Rarity rarity,
            String assetUrl,
            AssetType assetType,
            Integer displayOrder,
            Boolean active,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
        this.id = id;
        this.categoryId = categoryId;
        this.categoryName = categoryName;
        this.name = name;
        this.description = description;
        this.price = price;
        this.rarity = rarity;
        this.assetUrl = assetUrl;
        this.assetType = assetType;
        this.displayOrder = displayOrder;
        this.active = active;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public Long getId() {
        return id;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public String getCategoryName() {
        return categoryName;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public Rarity getRarity() {
        return rarity;
    }

    public String getAssetUrl() {
        return assetUrl;
    }

    public AssetType getAssetType() {
        return assetType;
    }

    public Integer getDisplayOrder() {
        return displayOrder;
    }

    public Boolean getActive() {
        return active;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
