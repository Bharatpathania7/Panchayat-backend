package com.panchayat.backend.gifts.controller;

import com.panchayat.backend.admin.media.MediaUrls;
import com.panchayat.backend.cache.ReadCache;
import com.panchayat.backend.gifts.entity.GiftDefinition;
import com.panchayat.backend.gifts.service.GiftCatalogService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/** The gifts on sale, for the app (public: the gift panel shows them before sign-in too). */
@RestController
public class GiftCatalogController {

    private final GiftCatalogService catalog;
    private final ReadCache cache;

    public GiftCatalogController(GiftCatalogService catalog, ReadCache cache) {
        this.catalog = catalog;
        this.cache = cache;
    }

    /** The same for everybody, so it's kept for 30 s (a gift the admin adds shows up within that). */
    @GetMapping("/api/v1/gifts")
    public ResponseEntity<String> onSale() {
        return cache.respond("gifts", "on-sale", Duration.ofSeconds(30),
                () -> catalog.onSale().stream().map(GiftCatalogController::view).toList());
    }

    private static Map<String, Object> view(GiftDefinition g) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", g.getId());
        m.put("name", g.getName());
        m.put("price", g.getPrice());
        m.put("category", g.getCategory());
        m.put("sortOrder", g.getSortOrder());
        m.put("imageUrl", MediaUrls.link(g.getImageUrl()));
        m.put("animationUrl", MediaUrls.link(g.getAnimationUrl()));
        m.put("animationFormat", g.getAnimationFormat());
        m.put("soundUrl", MediaUrls.link(g.getSoundUrl()));
        return m;
    }
}
