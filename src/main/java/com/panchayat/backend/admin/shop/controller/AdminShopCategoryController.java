
        package com.panchayat.backend.admin.shop.controller;

import com.panchayat.backend.admin.shop.dto.request.CreateCategoryRequest;
import com.panchayat.backend.admin.shop.dto.request.UpdateCategoryRequest;
import com.panchayat.backend.admin.shop.dto.response.CategoryResponse;
import com.panchayat.backend.admin.shop.service.ShopCategoryService;
import com.panchayat.backend.admin.shop.dto.request.UpdateCategoryStatusRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/shop/categories")
@Validated
public class AdminShopCategoryController {

    private final ShopCategoryService categoryService;

    public AdminShopCategoryController(ShopCategoryService categoryService) {
        this.categoryService = categoryService;
    }

    // Create a new category
    @PostMapping
    public ResponseEntity<CategoryResponse> createCategory(
            @Valid @RequestBody CreateCategoryRequest request) {

        CategoryResponse response = categoryService.createCategory(request);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // Get all categories
    @GetMapping
    public ResponseEntity<List<CategoryResponse>> getAllCategories() {

        List<CategoryResponse> categories = categoryService.getAllCategories();

        return ResponseEntity.ok(categories);
    }

    // Get category by ID
    @GetMapping("/{id}")
    public ResponseEntity<CategoryResponse> getCategoryById(
            @PathVariable @NotNull Long id) {

        CategoryResponse response = categoryService.getCategoryById(id);

        return ResponseEntity.ok(response);
    }

    // Update category
    @PutMapping("/{id}")
    public ResponseEntity<CategoryResponse> updateCategory(
            @PathVariable Long id,
            @Valid @RequestBody UpdateCategoryRequest request) {

        CategoryResponse response = categoryService.updateCategory(id, request);

        return ResponseEntity.ok(response);
    }

    // Activate or deactivate category
    @PatchMapping("/{id}/status")
    public ResponseEntity<CategoryResponse> updateCategoryStatus(
            @PathVariable Long id,
            @RequestBody @Valid UpdateCategoryStatusRequest request) {

        CategoryResponse response = categoryService.updateCategoryStatus(
                id,
                request.getActive()
        );

        return ResponseEntity.ok(response);
    }
}
