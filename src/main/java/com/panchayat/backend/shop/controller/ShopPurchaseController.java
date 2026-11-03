package com.panchayat.backend.shop.controller;

import com.panchayat.backend.auth.repository.UserRepository;
import com.panchayat.backend.shop.service.ShopPurchaseService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Buying Shop items with coins (signed in):
 *   POST /api/v1/shop/products/{id}/buy   { quantity, requestId }
 *   GET  /api/v1/shop/me/items            what you own
 */
@RestController
public class ShopPurchaseController {

    private final ShopPurchaseService shop;
    private final UserRepository users;

    public ShopPurchaseController(ShopPurchaseService shop, UserRepository users) {
        this.shop = shop;
        this.users = users;
    }

    public record BuyRequest(Integer quantity, String requestId) {
    }

    @PostMapping("/api/v1/shop/products/{id:[0-9]+}/buy")
    public Map<String, Object> buy(@PathVariable Long id, @RequestBody BuyRequest body, Authentication auth) {
        return shop.buy(me(auth), id, body == null ? null : body.quantity(), body == null ? null : body.requestId());
    }

    @GetMapping("/api/v1/shop/me/items")
    public List<Map<String, Object>> mine(Authentication auth) {
        return shop.mine(me(auth));
    }

    private Long me(Authentication auth) {
        if (auth == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in first");
        return users.findByEmailIgnoreCase(auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in to buy"))
                .getId();
    }
}
