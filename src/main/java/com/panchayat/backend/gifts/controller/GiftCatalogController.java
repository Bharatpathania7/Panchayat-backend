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
        return cache.respond("gifts", "on-sale", Duration.ofSeconds(30), () -> {
            java.util.List<Map<String, Object>> out = new java.util.ArrayList<>(catalog.onSale().stream().map(GiftCatalogController::view).toList());
            // The gift the Newbie welcome reward plays may be off sale. The app needs its name and picture to show it in the room;
            // it is marked offSale so it never appears in the gift panel.
            try {
                String rid = jdbc.query("select auto_reward_gift_id from newbie_room_config where id = 1", rs -> rs.next() ? rs.getString(1) : null);
                if (rid != null && out.stream().noneMatch(m -> rid.equals(m.get("id")))) {
                    giftRepository.findById(rid).ifPresent(g -> {
                        Map<String, Object> v = view(g);
                        v.put("offSale", true);
                        out.add(v);
                    });
                }
            } catch (Exception ignored) {
                // The catalog still works without it
            }
            return out;
        });
    }

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @org.springframework.beans.factory.annotation.Autowired
    private com.panchayat.backend.gifts.repository.GiftDefinitionRepository giftRepository;

    @org.springframework.beans.factory.annotation.Autowired
    private com.panchayat.backend.gifts.service.GiftCategoryService categories;

    /** The category tabs in the gift panel, in order, with who can send each (NORMAL, VIP, CP). */
    @GetMapping("/api/v1/gifts/categories")
    public ResponseEntity<String> categories() {
        return cache.respond("gifts", "categories", Duration.ofSeconds(30), () -> categories.list());
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
        m.put("animationRatio", g.getAnimationRatio());
        m.put("soundUrl", MediaUrls.link(g.getSoundUrl()));
        return m;
    }
}
