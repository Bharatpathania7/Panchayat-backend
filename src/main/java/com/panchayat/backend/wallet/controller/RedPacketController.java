package com.panchayat.backend.wallet.controller;

import com.panchayat.backend.wallet.entity.RedPacketProduct;
import com.panchayat.backend.wallet.service.RedPacketProductService;

import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/wallet")
public class RedPacketController {

    private final RedPacketProductService service;

    public RedPacketController(
            RedPacketProductService service
    ) {
        this.service = service;
    }

    @GetMapping("/red-packets")
    public List<RedPacketProduct> getActiveProducts() {
        return service.getActiveProducts();
    }
}