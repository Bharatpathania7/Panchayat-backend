package com.panchayat.backend.shop.controller;

import com.panchayat.backend.admin.shop.dto.response.ProductResponse;
import com.panchayat.backend.admin.shop.service.ShopProductService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/shop/products")
public class ShopController {

    private final ShopProductService productService;

    public ShopController(ShopProductService productService) {
        this.productService = productService;
    }

    // Get all active products for the user app
    @GetMapping
    public ResponseEntity<List<ProductResponse>> getActiveProducts(
            @RequestParam(required = false) Long categoryId) {

        List<ProductResponse> products;

        if (categoryId != null) {
            products = productService.getActiveProductsByCategory(categoryId);
        } else {
            products = productService.getActiveProducts();
        }

        return ResponseEntity.ok(products);
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