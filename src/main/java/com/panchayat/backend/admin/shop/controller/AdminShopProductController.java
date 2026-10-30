
        package com.panchayat.backend.admin.shop.controller;

import com.panchayat.backend.admin.shop.dto.request.CreateProductRequest;
import com.panchayat.backend.admin.shop.dto.request.UpdateProductRequest;
import com.panchayat.backend.admin.shop.dto.request.UpdateProductStatusRequest;
import com.panchayat.backend.admin.shop.dto.response.ProductResponse;
import com.panchayat.backend.admin.shop.service.ShopProductService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/shop/products")
public class AdminShopProductController {

    private final ShopProductService productService;

    public AdminShopProductController(ShopProductService productService) {
        this.productService = productService;
    }

    // Create product
    @PostMapping
    public ResponseEntity<ProductResponse> createProduct(
            @Valid @RequestBody CreateProductRequest request) {

        ProductResponse response = productService.createProduct(request);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // Get all products (including inactive products)
    @GetMapping
    public ResponseEntity<List<ProductResponse>> getAllProducts() {

        List<ProductResponse> products = productService.getAllProducts();

        return ResponseEntity.ok(products);
    }

    // Get product by ID
    @GetMapping("/{id}")
    public ResponseEntity<ProductResponse> getProductById(
            @PathVariable Long id) {

        ProductResponse response = productService.getProductById(id);

        return ResponseEntity.ok(response);
    }

    // Get products by category
    @GetMapping("/category/{categoryId}")
    public ResponseEntity<List<ProductResponse>> getProductsByCategory(
            @PathVariable Long categoryId) {

        List<ProductResponse> products =
                productService.getProductsByCategory(categoryId);

        return ResponseEntity.ok(products);
    }

    // Update product
    @PutMapping("/{id}")
    public ResponseEntity<ProductResponse> updateProduct(
            @PathVariable Long id,
            @Valid @RequestBody UpdateProductRequest request) {

        ProductResponse response =
                productService.updateProduct(id, request);

        return ResponseEntity.ok(response);
    }

    // Activate or deactivate product
    @PatchMapping("/{id}/status")
    public ResponseEntity<ProductResponse> updateProductStatus(
            @PathVariable Long id,
            @Valid @RequestBody UpdateProductStatusRequest request) {

        ProductResponse response = productService.updateProductStatus(
                id,
                request.getActive()
        );

        return ResponseEntity.ok(response);
    }
}
