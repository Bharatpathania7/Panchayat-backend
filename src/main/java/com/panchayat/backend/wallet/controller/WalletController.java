package com.panchayat.backend.wallet.controller;

import com.panchayat.backend.wallet.entity.CoinPack;
import com.panchayat.backend.wallet.service.CoinPackService;

import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/wallet")
public class WalletController {

    private final CoinPackService service;

    public WalletController(CoinPackService service) {
        this.service = service;
    }

    @GetMapping("/coin-packs")
    public List<CoinPack> getActiveCoinPacks() {
        return service.getActivePacks();
    }
}