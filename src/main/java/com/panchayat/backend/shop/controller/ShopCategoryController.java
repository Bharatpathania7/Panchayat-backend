package com.panchayat.backend.shop.controller;

import com.panchayat.backend.admin.shop.dto.response.CategoryResponse;
import com.panchayat.backend.admin.shop.service.ShopCategoryService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/shop/categories")
public class ShopCategoryController {

    private final ShopCategoryService categoryService;

    public ShopCategoryController(
            ShopCategoryService categoryService
    ) {
        this.categoryService = categoryService;
    }

    // Get active categories for the user app
    @GetMapping
    public ResponseEntity<List<CategoryResponse>> getActiveCategories() {

        return ResponseEntity.ok(
                categoryService.getActiveCategories()
        );
    }
}