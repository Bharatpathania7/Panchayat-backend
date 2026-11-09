package com.panchayat.backend.shop.controller;

import com.panchayat.backend.admin.shop.dto.response.ProductResponse;
import com.panchayat.backend.admin.shop.service.ShopProductService;
import com.panchayat.backend.cache.ReadCache;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

@RestController
@RequestMapping("/api/v1/shop/products")
public class ShopController {

    private final ShopProductService productService;
    private final ReadCache cache;

    public ShopController(ShopProductService productService, ReadCache cache) {
        this.productService = productService;
        this.cache = cache;
    }

    // Get all active products for the user app. The same for everybody: kept for 30 s.
    // (A category that doesn't exist throws and is never cached.)
    @GetMapping
    public ResponseEntity<String> getActiveProducts(
            @RequestParam(required = false) Long categoryId) {

        return cache.respond("shop-products", categoryId == null ? "all" : "cat-" + categoryId, Duration.ofSeconds(30),
                () -> categoryId != null
                        ? productService.getActiveProductsByCategory(categoryId)
                        : productService.getActiveProducts());
    }

    // Get a single active product
    @GetMapping("/{id}")
    public ResponseEntity<ProductResponse> getActiveProductById(
            @PathVariable Long id) {

        return ResponseEntity.ok(
                productService.getActiveProductById(id)
        );
    }
}