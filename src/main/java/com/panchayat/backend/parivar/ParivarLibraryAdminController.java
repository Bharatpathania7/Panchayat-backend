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

/** The Parivar library: the admin uploads animations, badges, titles and gift art here, then uses them as Family Treasure rewards. */
@RestController
@RequestMapping("/api/v1/admin/parivar/library")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
public class ParivarLibraryAdminController {

    private static final Set<String> KINDS = Set.of("BADGE", "TITLE", "ANIMATION", "GIFT");
    private static final Set<String> RARITIES = Set.of("COMMON", "PREMIUM", "EPIC", "LEGENDARY", "ULTRA_RARE");
    private static final Set<String> FORMATS = Set.of("PNG", "JPG", "JPEG", "SVGA", "MP4");

    private final JdbcTemplate jdbc;
    private final AdminMediaService media;

    public ParivarLibraryAdminController(JdbcTemplate jdbc, AdminMediaService media) {
        this.jdbc = jdbc;
        this.media = media;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return jdbc.query("select c.*, (select count(*) from treasure_rewards r where r.kind = 'COLLECTIBLE' and r.ref = c.id) as used from parivar_collectibles c order by c.id desc",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("kind", rs.getString("kind"));
                    m.put("name", rs.getString("name"));
                    m.put("description", rs.getString("description"));
                    m.put("mediaUrl", MediaUrls.link(rs.getString("media_url")));
                    m.put("format", rs.getString("media_format"));
                    m.put("rarity", rs.getString("rarity"));
                    m.put("active", rs.getBoolean("active"));
                    m.put("usedInRewards", rs.getLong("used"));
                    m.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
                    return m;
                });
    }

    @PostMapping(consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file, @RequestParam("kind") String kind, @RequestParam("name") String name,
                                      @RequestParam(value = "rarity", defaultValue = "COMMON") String rarity, @RequestParam(value = "description", required = false) String description) {
        kind = kind.toUpperCase(Locale.ROOT);
        rarity = rarity.toUpperCase(Locale.ROOT);
        if (!KINDS.contains(kind)) throw bad("Choose Badge, Title, Animation or Gift.");
        if (!RARITIES.contains(rarity)) throw bad("Choose a rarity.");
        name = name == null ? "" : name.strip();
        if (name.isEmpty() || name.length() > 60) throw bad("Give it a name (up to 60 letters).");
        if (description != null && description.length() > 200) throw bad("The description can be up to 200 letters.");
        var up = media.upload(file, "parivar", FORMATS);
        jdbc.update("insert into parivar_collectibles (kind, name, description, media_url, media_format, rarity) values (?, ?, ?, ?, ?, ?)",
                kind, name, description == null || description.isBlank() ? null : description.strip(), up.url(), up.format(), rarity);
        return Map.of("ok", true);
    }

    public record Edit(String name, String rarity, String description, Boolean active) {
    }

    @PutMapping("/{id:[0-9]+}")
    public Map<String, Object> edit(@PathVariable Long id, @RequestBody Edit e) {
        if (e.name() != null && (e.name().isBlank() || e.name().length() > 60)) throw bad("Give it a name (up to 60 letters).");
        if (e.rarity() != null && !RARITIES.contains(e.rarity().toUpperCase(Locale.ROOT))) throw bad("Choose a rarity.");
        int n = jdbc.update("update parivar_collectibles set name = coalesce(?, name), rarity = coalesce(?, rarity), description = coalesce(?, description), active = coalesce(?, active) where id = ?",
                e.name() == null ? null : e.name().strip(), e.rarity() == null ? null : e.rarity().toUpperCase(Locale.ROOT), e.description(), e.active(), id);
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That item doesn't exist.");
        return Map.of("ok", true);
    }

    @DeleteMapping("/{id:[0-9]+}")
    public Map<String, Object> delete(@PathVariable Long id) {
        if (jdbc.queryForObject("select count(*) from treasure_rewards where kind = 'COLLECTIBLE' and ref = ?", Long.class, id) > 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A chest reward uses this. Remove that reward first, or turn this item off.");
        List<Map<String, Object>> r = jdbc.queryForList("select media_url, media_format from parivar_collectibles where id = ?", id);
        if (r.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That item doesn't exist.");
        // People who already won it keep their copy (their collection row holds the picture's link, so the file stays)
        jdbc.update("delete from parivar_collectibles where id = ?", id);
        return Map.of("deleted", id);
    }

    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }
}
