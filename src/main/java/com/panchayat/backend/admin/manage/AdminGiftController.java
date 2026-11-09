package com.panchayat.backend.admin.manage;

import com.panchayat.backend.admin.manage.AdminGiftService.GiftInput;
import com.panchayat.backend.gifts.entity.GiftDefinition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.panchayat.backend.admin.media.MediaUrls;

/**
 * Gifts in the admin panel. Super Admins, Admins and Moderators manage them
 * (the management role group); only a Super Admin can delete one.
 */
@RestController
@RequestMapping("/api/v1/admin/manage/gifts")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','MODERATOR')")
public class AdminGiftController {

    private final AdminGiftService service;

    public AdminGiftController(AdminGiftService service) {
        this.service = service;
    }

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate announceJdbc;

    /** Gift ids whose sending is announced to the whole server. */
    @GetMapping("/announce")
    public List<String> announced() {
        return announceJdbc.queryForList("select id from gifts where announce order by id", String.class);
    }

    public record AnnounceRequest(Boolean announce) {
    }

    /** Switch the server-wide notification for one gift on or off. */
    @PutMapping("/{id}/announce")
    public Map<String, Object> setAnnounce(@PathVariable String id, @RequestBody AnnounceRequest body) {
        boolean on = body != null && Boolean.TRUE.equals(body.announce());
        int n = announceJdbc.update("update gifts set announce = ? where id = ?", on, id);
        if (n == 0) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "That gift doesn't exist.");
        return Map.of("id", id, "announce", on);
    }

    /** Fixed charm per gift (gift id to charm); a gift not listed uses 1 charm per 5 coins. */
    @GetMapping("/charm")
    public Map<String, Long> charms() {
        Map<String, Long> m = new java.util.LinkedHashMap<>();
        announceJdbc.query("select id, charm from gifts where charm is not null", rs -> {
            m.put(rs.getString(1), rs.getLong(2));
        });
        return m;
    }

    public record CharmRequest(Long charm) {
    }

    /** Set the fixed charm a gift gives its receiver (empty = back to 1 per 5 coins). */
    @PutMapping("/{id}/charm")
    public Map<String, Object> setCharm(@PathVariable String id, @RequestBody CharmRequest body) {
        Long c = body == null ? null : body.charm();
        if (c != null && (c < 0 || c > 100_000_000L)) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Charm must be 0 or more.");
        int n = announceJdbc.update("update gifts set charm = ? where id = ?", c, id);
        if (n == 0) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "That gift doesn't exist.");
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("id", id);
        out.put("charm", c);
        return out;
    }

    /** Charm a gift earns a CP couple when partners send it to each other (gift id to Charm); not listed = 1 per 10 coins. */
    @GetMapping("/cp-charm")
    public Map<String, Long> cpCharms() {
        Map<String, Long> m = new java.util.LinkedHashMap<>();
        announceJdbc.query("select id, cp_charm from gifts where cp_charm is not null", rs -> {
            m.put(rs.getString(1), rs.getLong(2));
        });
        return m;
    }

    @PutMapping("/{id}/cp-charm")
    public Map<String, Object> setCpCharm(@PathVariable String id, @RequestBody CharmRequest body) {
        Long c = body == null ? null : body.charm();
        if (c != null && (c < 0 || c > 100_000_000L)) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Charm must be 0 or more.");
        int n = announceJdbc.update("update gifts set cp_charm = ? where id = ?", c, id);
        if (n == 0) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "That gift doesn't exist.");
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("id", id);
        out.put("cpCharm", c);
        return out;
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.panchayat.backend.gifts.service.GiftCategoryService categoryService;

    /** Gift categories (name and kind: NORMAL, VIP gifts, CP gifts). */
    @GetMapping("/categories")
    public List<Map<String, Object>> categories() {
        return categoryService.list();
    }

    public record CategoryRequest(String name, String kind) {
    }

    @PostMapping("/categories")
    public Map<String, Object> addCategory(@RequestBody CategoryRequest b) {
        return categoryService.create(b == null ? null : b.name(), b == null ? null : b.kind());
    }

    @DeleteMapping("/categories/{name}")
    public Map<String, Object> deleteCategory(@PathVariable String name) {
        categoryService.delete(name);
        return Map.of("deleted", name);
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return service.list().stream().map(AdminGiftController::view).toList();
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody GiftInput body) {
        return view(service.create(body));
    }

    @PatchMapping("/{id}")
    public Map<String, Object> update(@PathVariable String id, @RequestBody GiftInput body) {
        return view(service.update(id, body));
    }

    /** kind: image (PNG/JPG/JPEG), animation (SVGA/MP4), sound (MP3). Field: file. */
    @PostMapping(value = "/{id}/{kind:image|animation|sound}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(@PathVariable String id, @PathVariable String kind, @RequestParam("file") MultipartFile file) {
        return view(service.upload(id, kind, file));
    }

    @DeleteMapping("/{id}/{kind:animation|sound}")
    public Map<String, Object> removeFile(@PathVariable String id, @PathVariable String kind) {
        return view(service.removeFile(id, kind));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** The gift with signed, expiring links to its files. */
    static Map<String, Object> view(GiftDefinition g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", g.getId());
        m.put("name", g.getName());
        m.put("price", g.getPrice());
        m.put("category", g.getCategory());
        m.put("sortOrder", g.getSortOrder());
        m.put("active", g.isActive());
        m.put("imageUrl", MediaUrls.link(g.getImageUrl()));
        m.put("animationUrl", MediaUrls.link(g.getAnimationUrl()));
        m.put("animationFormat", g.getAnimationFormat());
        m.put("soundUrl", MediaUrls.link(g.getSoundUrl()));
        m.put("createdAt", g.getCreatedAt());
        m.put("updatedAt", g.getUpdatedAt());
        return m;
    }
}
