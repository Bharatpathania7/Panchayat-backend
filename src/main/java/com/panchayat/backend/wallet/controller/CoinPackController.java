package com.panchayat.backend.wallet.controller;

import com.panchayat.backend.wallet.entity.CoinPack;
import com.panchayat.backend.wallet.service.CoinPackService;

import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/coin-packs")
public class CoinPackController {

    private final CoinPackService coinPackService;

    public CoinPackController(CoinPackService coinPackService) {
        this.coinPackService = coinPackService;
    }

    // Get all active coin packs for the app.
    @GetMapping
    public List<CoinPack> getActivePacks() {
        return coinPackService.getActivePacks();
    }

    // Get a specific active coin pack by ID.
    @GetMapping("/{id}")
    public CoinPack getActivePackById(@PathVariable Long id) {
        return coinPackService.getActivePackById(id);
    }
}