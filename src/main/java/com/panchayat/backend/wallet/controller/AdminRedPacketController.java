
        package com.panchayat.backend.wallet.controller;

import com.panchayat.backend.wallet.dto.CreateRedPacketRequest;
import com.panchayat.backend.wallet.dto.UpdateRedPacketRequest;
import com.panchayat.backend.wallet.dto.AdminRedPacketGrantRequest;
import com.panchayat.backend.wallet.dto.AdminRedPacketInventoryResponse;
import com.panchayat.backend.wallet.entity.RedPacketProduct;
import com.panchayat.backend.wallet.service.RedPacketProductService;
import com.panchayat.backend.wallet.service.AdminRedPacketInventoryService;

import org.springframework.web.multipart.MultipartFile;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/red-packets")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminRedPacketController {

    private final RedPacketProductService service;
    private final AdminRedPacketInventoryService inventoryService;

    public AdminRedPacketController(
            RedPacketProductService service,
            AdminRedPacketInventoryService inventoryService
    ) {
        this.service = service;
        this.inventoryService = inventoryService;
    }

    // Get all red packet products
    @GetMapping
    public List<RedPacketProduct> getAllProducts() {
        return service.getAllProducts();
    }

    // Create red packet product
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RedPacketProduct createProduct(
            @Valid @RequestBody CreateRedPacketRequest request
    ) {
        return service.createProduct(request);
    }

    // Update red packet product
    @PatchMapping("/{id}")
    public RedPacketProduct updateProduct(
            @PathVariable Long id,
            @Valid @RequestBody UpdateRedPacketRequest request
    ) {
        return service.updateProduct(id, request);
    }

    // Activate or deactivate product
    @PatchMapping("/{id}/status")
    public RedPacketProduct updateStatus(
            @PathVariable Long id,
            @RequestParam boolean active
    ) {
        return service.updateStatus(id, active);
    }

    // Upload product image
    @PostMapping(
            value = "/{id}/image",
            consumes = "multipart/form-data"
    )
    public RedPacketProduct uploadProductImage(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file
    ) {
        return service.uploadProductImage(id, file);
    }

    // Manually grant red packets to a user
    @PostMapping("/users/{userId}/grant")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminRedPacketInventoryResponse grantRedPackets(
            @PathVariable Long userId,
            @Valid @RequestBody AdminRedPacketGrantRequest request
    ) {
        return inventoryService.grant(userId, request);
    }

    // View a user's red packet inventory
    @GetMapping("/users/{userId}/inventory")
    public List<AdminRedPacketInventoryResponse> getUserInventory(
            @PathVariable Long userId
    ) {
        return inventoryService.getInventory(userId);
    }
}
