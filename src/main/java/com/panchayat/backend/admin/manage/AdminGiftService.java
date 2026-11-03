package com.panchayat.backend.admin.manage;

import com.panchayat.backend.admin.media.AdminMediaService;
import com.panchayat.backend.admin.media.AdminMediaService.Uploaded;
import com.panchayat.backend.gifts.entity.GiftDefinition;
import com.panchayat.backend.gifts.repository.GiftDefinitionRepository;
import com.panchayat.backend.gifts.service.GiftCatalogService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Gifts from the admin panel: add, edit, turn on or off, pictures, animations and sounds. */
@Service
public class AdminGiftService {

    public static final Set<String> CATEGORIES = Set.of("Popular", "Cute", "Romantic", "Celebration", "Royal", "Legendary");
    private static final long MAX_PRICE = 100_000_000L;

    private final GiftDefinitionRepository gifts;
    private final AdminMediaService media;
    private final GiftCatalogService catalog;

    public AdminGiftService(GiftDefinitionRepository gifts, AdminMediaService media, GiftCatalogService catalog) {
        this.gifts = gifts;
        this.media = media;
        this.catalog = catalog;
    }

    public record GiftInput(String name, Long price, String category, Integer sortOrder, Boolean active) {
    }

    public List<GiftDefinition> list() {
        return gifts.findAllByOrderBySortOrderAscPriceAsc();
    }

    @Transactional
    public GiftDefinition create(GiftInput in) {
        if (in == null) throw bad("Fill in the gift's details.");
        String name = name(in.name(), true);
        GiftDefinition g = new GiftDefinition();
        g.setId(uniqueId(name));
        g.setName(name);
        g.setPrice(price(in.price(), true));
        g.setCategory(category(in.category() == null ? "Popular" : in.category()));
        g.setSortOrder(in.sortOrder() == null ? nextOrder() : order(in.sortOrder()));
        g.setActive(in.active() == null || in.active());
        GiftDefinition saved = gifts.save(g);
        catalog.reloadAfterCommit();
        return saved;
    }

    @Transactional
    public GiftDefinition update(String id, GiftInput in) {
        GiftDefinition g = find(id);
        if (in == null) return g;
        if (in.name() != null) g.setName(name(in.name(), true));
        if (in.price() != null) g.setPrice(price(in.price(), true));
        if (in.category() != null) g.setCategory(category(in.category()));
        if (in.sortOrder() != null) g.setSortOrder(order(in.sortOrder()));
        if (in.active() != null) {
            if (in.active() && g.getImageUrl() == null) {
                throw bad("Add the gift's picture before putting it on sale.");
            }
            g.setActive(in.active());
        }
        GiftDefinition saved = gifts.save(g);
        catalog.reloadAfterCommit();
        return saved;
    }

    /** kind: image (PNG/JPG/JPEG), animation (SVGA/MP4), sound (MP3). Replaces the old file. */
    @Transactional
    public GiftDefinition upload(String id, String kind, MultipartFile file) {
        GiftDefinition g = find(id);
        switch (kind) {
            case "image" -> {
                Uploaded u = media.upload(file, "gifts/images", AdminMediaService.PICTURES);
                String old = g.getImagePublicId();
                String oldFormat = old == null ? null : "PNG";
                g.setImageUrl(u.url());
                g.setImagePublicId(u.publicId());
                media.delete(old, oldFormat);
            }
            case "animation" -> {
                Uploaded u = media.upload(file, "gifts/animations", AdminMediaService.ANIMATIONS);
                media.delete(g.getAnimationPublicId(), g.getAnimationFormat());
                g.setAnimationUrl(u.url());
                g.setAnimationPublicId(u.publicId());
                g.setAnimationFormat(u.format());
            }
            case "sound" -> {
                Uploaded u = media.upload(file, "gifts/sounds", AdminMediaService.SOUNDS);
                media.delete(g.getSoundPublicId(), "MP3");
                g.setSoundUrl(u.url());
                g.setSoundPublicId(u.publicId());
            }
            default -> throw bad("Unknown file kind");
        }
        return gifts.save(g);
    }

    @Transactional
    public GiftDefinition removeFile(String id, String kind) {
        GiftDefinition g = find(id);
        switch (kind) {
            case "animation" -> {
                media.delete(g.getAnimationPublicId(), g.getAnimationFormat());
                g.setAnimationUrl(null);
                g.setAnimationPublicId(null);
                g.setAnimationFormat(null);
            }
            case "sound" -> {
                media.delete(g.getSoundPublicId(), "MP3");
                g.setSoundUrl(null);
                g.setSoundPublicId(null);
            }
            default -> throw bad("Only the animation or the sound can be removed. Replace the picture instead.");
        }
        return gifts.save(g);
    }

    @Transactional
    public void delete(String id) {
        GiftDefinition g = find(id);
        gifts.delete(g);
        gifts.flush();
        media.delete(g.getImagePublicId(), "PNG");
        media.delete(g.getAnimationPublicId(), g.getAnimationFormat());
        media.delete(g.getSoundPublicId(), "MP3");
        catalog.reloadAfterCommit();
    }

    private GiftDefinition find(String id) {
        return gifts.findById(id == null ? "" : id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "That gift doesn't exist any more."));
    }

    private int nextOrder() {
        return gifts.findAll().stream().mapToInt(GiftDefinition::getSortOrder).max().orElse(0) + 1;
    }

    private String uniqueId(String name) {
        String base = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (base.isEmpty()) base = "gift";
        if (base.length() > 50) base = base.substring(0, 50);
        String id = base;
        for (int n = 2; gifts.existsById(id); n++) id = base + "-" + n;
        return id;
    }

    private static String name(String raw, boolean required) {
        String n = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
        if (n.isEmpty() && required) throw bad("Give the gift a name.");
        if (n.length() > 60) throw bad("The name can be up to 60 characters.");
        return n;
    }

    private static long price(Long p, boolean required) {
        if (p == null && required) throw bad("Set the gift's price in coins.");
        if (p == null || p < 1 || p > MAX_PRICE) throw bad("The price must be between 1 and 10,00,00,000 coins.");
        return p;
    }

    private static String category(String c) {
        String v = c == null ? "" : c.trim();
        for (String ok : CATEGORIES) if (ok.equalsIgnoreCase(v)) return ok;
        throw bad("Category must be one of " + String.join(", ", CATEGORIES.stream().sorted().toList()) + ".");
    }

    private static int order(int o) {
        if (o < 0 || o > 100_000) throw bad("Order must be between 0 and 1,00,000.");
        return o;
    }

    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }
}
