package com.panchayat.backend.admin.shop.dto.request;

import jakarta.validation.constraints.NotNull;

public class UpdateCategoryStatusRequest {

    @NotNull(message = "Active status is required")
    private Boolean active;

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }
}