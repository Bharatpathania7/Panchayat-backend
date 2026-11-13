package com.panchayat.backend.parivar;

import com.panchayat.backend.admin.media.AdminMediaService;
import com.panchayat.backend.admin.media.MediaUrls;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * The Parivar library: the admin makes categories and files pictures and animations in them (badges, chest art, gift art, Parivar art ...).
 * An item can be a Family Treasure reward (COLLECTIBLE), and its file link can be used in any "Image URL" box of the Parivar settings.
 */
@RestController
@RequestMapping("/api/v1/admin/parivar/library")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
public class ParivarLibraryAdminController {

    private static final Set<String> RARITIES = Set.of("COMMON", "PREMIUM", "EPIC", "LEGENDARY", "ULTRA_RARE");

    private final JdbcTemplate jdbc;
    private final AdminMediaService media;

    public ParivarLibraryAdminController(JdbcTemplate jdbc, AdminMediaService media) {
        this.jdbc = jdbc;
        this.media = media;
    }

    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }

    /* ---------------- categories ---------------- */

    @GetMapping("/categories")
    public List<Map<String, Object>> categories() {
        return jdbc.query("select k.*, (select count(*) from parivar_collectibles c where c.category_id = k.id) as items from parivar_library_categories k order by k.sort_order, k.id",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("name", rs.getString("name"));
                    m.put("sortOrder", rs.getInt("sort_order"));
                    m.put("active", rs.getBoolean("active"));
                    m.put("items", rs.getLong("items"));
                    return m;
                });
    }

    public record CategoryBody(String name, Integer sortOrder, Boolean active) {
    }

    private static String cleanName(String n) {
        String s = n == null ? "" : n.strip();
        if (s.isEmpty() || s.length() > 40) throw bad("Give the category a name (up to 40 letters).");
        return s;
    }

    @PostMapping("/categories")
    public Map<String, Object> addCategory(@RequestBody CategoryBody b) {
        String name = cleanName(b.name());
        if (jdbc.queryForObject("select count(*) from parivar_library_categories where lower(name) = lower(?)", Long.class, name) > 0) throw bad("There is a category with this name already.");
        Long id = jdbc.queryForObject("insert into parivar_library_categories (name, sort_order) values (?, (select coalesce(max(sort_order), 0) + 1 from parivar_library_categories)) returning id", Long.class, name);
        return Map.of("id", id);
    }

    @PutMapping("/categories/{id:[0-9]+}")
    public Map<String, Object> editCategory(@PathVariable Long id, @RequestBody CategoryBody b) {
        String name = b.name() == null ? null : cleanName(b.name());
        if (name != null && jdbc.queryForObject("select count(*) from parivar_library_categories where lower(name) = lower(?) and id <> ?", Long.class, name, id) > 0) throw bad("There is a category with this name already.");
        int n = jdbc.update("update parivar_library_categories set name = coalesce(?, name), sort_order = coalesce(?, sort_order), active = coalesce(?, active) where id = ?", name, b.sortOrder(), b.active(), id);
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That category doesn't exist.");
        return Map.of("ok", true);
    }

    @DeleteMapping("/categories/{id:[0-9]+}")
    public Map<String, Object> deleteCategory(@PathVariable Long id) {
        if (jdbc.queryForObject("select count(*) from parivar_collectibles where category_id = ?", Long.class, id) > 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This category has items. Move or delete them first.");
        if (jdbc.update("delete from parivar_library_categories where id = ?", id) == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That category doesn't exist.");
        return Map.of("deleted", id);
    }

    /* ---------------- items ---------------- */

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) Long categoryId) {
        String where = categoryId == null ? "" : " where c.category_id = " + categoryId;
        return jdbc.query("select c.*, k.name as category_name, (select count(*) from treasure_rewards r where r.kind = 'COLLECTIBLE' and r.ref = c.id) as used"
                        + " from parivar_collectibles c left join parivar_library_categories k on k.id = c.category_id" + where + " order by c.id desc",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("categoryId", rs.getObject("category_id"));
                    m.put("category", rs.getString("category_name"));
                    m.put("name", rs.getString("name"));
                    m.put("description", rs.getString("description"));
                    m.put("mediaUrl", MediaUrls.link(rs.getString("media_url")));
                    m.put("path", rs.getString("media_url"));
                    m.put("format", rs.getString("media_format"));
                    m.put("rarity", rs.getString("rarity"));
                    m.put("active", rs.getBoolean("active"));
                    m.put("usedInRewards", rs.getLong("used"));
                    m.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
                    return m;
                });
    }

    /** One or many files at once: with several files each item is named after its file (the name box is used for a single file). */
    @PostMapping(consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(@RequestParam("file") List<MultipartFile> files, @RequestParam("categoryId") Long categoryId, @RequestParam(value = "name", required = false) String name,
                                      @RequestParam(value = "rarity", defaultValue = "COMMON") String rarity, @RequestParam(value = "description", required = false) String description) {
        rarity = rarity.toUpperCase(Locale.ROOT);
        if (!RARITIES.contains(rarity)) throw bad("Choose a rarity.");
        if (files == null || files.isEmpty() || files.size() > 50) throw bad("Choose 1 to 50 files.");
        if (jdbc.queryForObject("select count(*) from parivar_library_categories where id = ?", Long.class, categoryId) == 0) throw bad("Choose a category.");
        if (description != null && description.length() > 200) throw bad("The description can be up to 200 letters.");
        int added = 0;
        for (MultipartFile f : files) {
            String itemName;
            if (files.size() == 1 && name != null && !name.isBlank()) itemName = name.strip();
            else {
                String fn = f.getOriginalFilename() == null ? "Item" : f.getOriginalFilename();
                int dot = fn.lastIndexOf('.');
                itemName = (dot > 0 ? fn.substring(0, dot) : fn).replace('_', ' ').replace('-', ' ').strip();
            }
            if (itemName.isEmpty()) itemName = "Item";
            if (itemName.length() > 60) itemName = itemName.substring(0, 60);
            var up = media.upload(f, "parivar", AdminMediaService.VISUALS);
            jdbc.update("insert into parivar_collectibles (kind, category_id, name, description, media_url, media_format, rarity) values ('ITEM', ?, ?, ?, ?, ?, ?)",
                    categoryId, itemName, description == null || description.isBlank() ? null : description.strip(), up.url(), up.format(), rarity);
            added++;
        }
        return Map.of("ok", true, "added", added);
    }

    public record Edit(String name, String rarity, String description, Boolean active, Long categoryId) {
    }

    @PutMapping("/{id:[0-9]+}")
    public Map<String, Object> edit(@PathVariable Long id, @RequestBody Edit e) {
        if (e.name() != null && (e.name().isBlank() || e.name().length() > 60)) throw bad("Give it a name (up to 60 letters).");
        if (e.rarity() != null && !RARITIES.contains(e.rarity().toUpperCase(Locale.ROOT))) throw bad("Choose a rarity.");
        if (e.categoryId() != null && jdbc.queryForObject("select count(*) from parivar_library_categories where id = ?", Long.class, e.categoryId()) == 0) throw bad("Choose a category.");
        int n = jdbc.update("update parivar_collectibles set name = coalesce(?, name), rarity = coalesce(?, rarity), description = coalesce(?, description), active = coalesce(?, active), category_id = coalesce(?, category_id) where id = ?",
                e.name() == null ? null : e.name().strip(), e.rarity() == null ? null : e.rarity().toUpperCase(Locale.ROOT), e.description(), e.active(), e.categoryId(), id);
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That item doesn't exist.");
        return Map.of("ok", true);
    }

    @DeleteMapping("/{id:[0-9]+}")
    public Map<String, Object> delete(@PathVariable Long id) {
        if (jdbc.queryForObject("select count(*) from treasure_rewards where kind = 'COLLECTIBLE' and ref = ?", Long.class, id) > 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A chest reward uses this. Remove that reward first, or turn this item off.");
        if (jdbc.update("delete from parivar_collectibles where id = ?", id) == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That item doesn't exist.");
        // People who already won it keep their copy (their collection row holds the file link, so the file stays)
        return Map.of("deleted", id);
    }
}
