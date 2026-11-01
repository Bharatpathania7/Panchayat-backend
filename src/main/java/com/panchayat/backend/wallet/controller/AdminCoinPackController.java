
        package com.panchayat.backend.wallet.controller;

import com.panchayat.backend.wallet.dto.CreateCoinPackRequest;
import com.panchayat.backend.wallet.dto.UpdateCoinPackRequest;
import com.panchayat.backend.wallet.entity.CoinPack;
import com.panchayat.backend.wallet.service.CoinPackService;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/coin-packs")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminCoinPackController {

    private final CoinPackService coinPackService;

    public AdminCoinPackController(CoinPackService coinPackService) {
        this.coinPackService = coinPackService;
    }

    // Get all coin packs, including inactive packs
    @GetMapping
    public List<CoinPack> getAllPacks() {
        return coinPackService.getAllPacks();
    }

    // Create a new coin pack
    @PostMapping
    public CoinPack createPack(
            @Valid @RequestBody CreateCoinPackRequest request) {
        return coinPackService.createPack(request);
    }

    // Update an existing coin pack
    @PatchMapping("/{id}")
    public CoinPack updatePack(
            @PathVariable Long id,
            @Valid @RequestBody UpdateCoinPackRequest request) {
        return coinPackService.updatePack(id, request);
    }

    // Upload or replace a coin pack image
    @PostMapping(
            value = "/{id}/image",
            consumes = "multipart/form-data"
    )
    public CoinPack uploadPackImage(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file) {
        return coinPackService.uploadPackImage(id, file);
    }

    // Activate or deactivate a coin pack
    @PatchMapping("/{id}/status")
    public CoinPack updatePackStatus(
            @PathVariable Long id,
            @RequestParam boolean active) {
        return coinPackService.updateStatus(id, active);
    }
}
