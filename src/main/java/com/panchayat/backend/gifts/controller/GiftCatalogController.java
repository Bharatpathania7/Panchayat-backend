package com.panchayat.backend.gifts.controller;

import com.panchayat.backend.gifts.entity.GiftDefinition;
import com.panchayat.backend.gifts.service.GiftCatalogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** The gifts on sale, for the app (public: the gift panel shows them before sign-in too). */
@RestController
public class GiftCatalogController {

    private final GiftCatalogService catalog;

    public GiftCatalogController(GiftCatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/api/v1/gifts")
    public List<Map<String, Object>> onSale() {
        return catalog.onSale().stream().map(GiftCatalogController::view).toList();
    }

    private static Map<String, Object> view(GiftDefinition g) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", g.getId());
        m.put("name", g.getName());
        m.put("price", g.getPrice());
        m.put("category", g.getCategory());
        m.put("sortOrder", g.getSortOrder());
        m.put("imageUrl", g.getImageUrl());
        m.put("animationUrl", g.getAnimationUrl());
        m.put("animationFormat", g.getAnimationFormat());
        m.put("soundUrl", g.getSoundUrl());
        return m;
    }
}
