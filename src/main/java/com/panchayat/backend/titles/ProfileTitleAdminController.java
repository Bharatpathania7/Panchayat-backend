package com.panchayat.backend.titles;

import com.panchayat.backend.admin.media.AdminMediaService;
import com.panchayat.backend.admin.media.MediaUrls;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.regex.Pattern;

/** The admin makes the profile titles (colours, picture, price, free), gives or takes them, and sets how many a person can wear. */
@RestController
@RequestMapping("/api/v1/admin/titles")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
public class ProfileTitleAdminController {

    private static final Pattern COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private static final Set<String> RARITIES = Set.of("COMMON", "PREMIUM", "EPIC", "LEGENDARY", "ULTRA_RARE");

    private final JdbcTemplate jdbc;
    private final AdminMediaService media;
    private final ProfileTitleService titles;

    public ProfileTitleAdminController(JdbcTemplate jdbc, AdminMediaService media, ProfileTitleService titles) {
        this.jdbc = jdbc;
        this.media = media;
        this.titles = titles;
    }

    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }

    @GetMapping
    public Map<String, Object> list() {
        List<Map<String, Object>> rows = jdbc.query("select t.*, (select count(*) from user_titles u where u.title_id = t.id) as holders, (select count(*) from user_titles u where u.title_id = t.id and u.worn_pos is not null) as wearing from profile_titles t order by t.sort_order, t.id",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("name", rs.getString("name"));
                    m.put("iconUrl", MediaUrls.link(rs.getString("icon_url")));
                    m.put("colorFrom", rs.getString("color_from"));
                    m.put("colorTo", rs.getString("color_to"));
                    m.put("textColor", rs.getString("text_color"));
                    m.put("rarity", rs.getString("rarity"));
                    m.put("priceCoins", rs.getLong("price_coins"));
                    m.put("free", rs.getBoolean("free"));
                    m.put("active", rs.getBoolean("active"));
                    m.put("sortOrder", rs.getInt("sort_order"));
                    m.put("holders", rs.getLong("holders"));
                    m.put("wearing", rs.getLong("wearing"));
                    return m;
                });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("maxWorn", titles.maxWorn());
        out.put("titles", rows);
        return out;
    }

    public record Settings(Integer maxWorn) {
    }

    @PutMapping("/settings")
    public Map<String, Object> settings(@RequestBody Settings s) {
        if (s.maxWorn() == null || s.maxWorn() < 0 || s.maxWorn() > 100) throw bad("Use 0 (no limit) or up to 100.");
        jdbc.update("update title_settings set max_worn = ? where id = 1", s.maxWorn());
        return Map.of("maxWorn", s.maxWorn());
    }

    private void check(String name, String from, String to, String text, String rarity, long price) {
        if (name == null || name.isBlank() || name.strip().length() > 30) throw bad("Give the title a name (up to 30 letters).");
        for (String c : new String[]{from, to, text}) if (c == null || !COLOR.matcher(c).matches()) throw bad("Colours must look like #7b5cff.");
        if (!RARITIES.contains(rarity)) throw bad("Choose a rarity.");
        if (price < 0 || price > 1_000_000_000L) throw bad("The price must be 0 or more.");
    }

    @PostMapping(consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> create(@RequestParam("name") String name, @RequestParam(value = "colorFrom", defaultValue = "#7b5cff") String from, @RequestParam(value = "colorTo", defaultValue = "#c56bff") String to,
                                      @RequestParam(value = "textColor", defaultValue = "#ffffff") String text, @RequestParam(value = "rarity", defaultValue = "COMMON") String rarity,
                                      @RequestParam(value = "priceCoins", defaultValue = "0") long price, @RequestParam(value = "free", defaultValue = "false") boolean free,
                                      @RequestParam(value = "file", required = false) MultipartFile file) {
        rarity = rarity.toUpperCase(Locale.ROOT);
        check(name, from, to, text, rarity, price);
        String icon = file == null || file.isEmpty() ? null : media.upload(file, "titles", AdminMediaService.VISUALS).url();
        Long id = jdbc.queryForObject("insert into profile_titles (name, icon_url, color_from, color_to, text_color, rarity, price_coins, free, sort_order)"
                        + " values (?, ?, ?, ?, ?, ?, ?, ?, (select coalesce(max(sort_order), 0) + 1 from profile_titles)) returning id", Long.class,
                name.strip(), icon, from, to, text, rarity, price, free);
        return Map.of("id", id);
    }

    public record Edit(String name, String colorFrom, String colorTo, String textColor, String rarity, Long priceCoins, Boolean free, Boolean active, Integer sortOrder) {
    }

    @PutMapping("/{id:[0-9]+}")
    public Map<String, Object> edit(@PathVariable Long id, @RequestBody Edit e) {
        List<Map<String, Object>> cur = jdbc.queryForList("select * from profile_titles where id = ?", id);
        if (cur.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That title doesn't exist.");
        Map<String, Object> c = cur.get(0);
        String name = e.name() != null ? e.name() : (String) c.get("name");
        String from = e.colorFrom() != null ? e.colorFrom() : (String) c.get("color_from");
        String to = e.colorTo() != null ? e.colorTo() : (String) c.get("color_to");
        String text = e.textColor() != null ? e.textColor() : (String) c.get("text_color");
        String rarity = e.rarity() != null ? e.rarity().toUpperCase(Locale.ROOT) : (String) c.get("rarity");
        long price = e.priceCoins() != null ? e.priceCoins() : ((Number) c.get("price_coins")).longValue();
        check(name, from, to, text, rarity, price);
        jdbc.update("update profile_titles set name = ?, color_from = ?, color_to = ?, text_color = ?, rarity = ?, price_coins = ?, free = ?, active = ?, sort_order = ? where id = ?",
                name.strip(), from, to, text, rarity, price, e.free() != null ? e.free() : c.get("free"), e.active() != null ? e.active() : c.get("active"),
                e.sortOrder() != null ? e.sortOrder() : c.get("sort_order"), id);
        return Map.of("ok", true);
    }

    @PostMapping(value = "/{id:[0-9]+}/icon", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> icon(@PathVariable Long id, @RequestParam("file") MultipartFile file) {
        if (jdbc.queryForObject("select count(*) from profile_titles where id = ?", Long.class, id) == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That title doesn't exist.");
        jdbc.update("update profile_titles set icon_url = ? where id = ?", media.upload(file, "titles", AdminMediaService.VISUALS).url(), id);
        return Map.of("ok", true);
    }

    @DeleteMapping("/{id:[0-9]+}/icon")
    public Map<String, Object> clearIcon(@PathVariable Long id) {
        jdbc.update("update profile_titles set icon_url = null where id = ?", id);
        return Map.of("ok", true);
    }

    @DeleteMapping("/{id:[0-9]+}")
    public Map<String, Object> delete(@PathVariable Long id) {
        if (jdbc.queryForObject("select count(*) from treasure_rewards where kind = 'PROFILE_TITLE' and ref = ?", Long.class, id) > 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A chest reward uses this title. Remove that reward first, or turn the title off.");
        if (jdbc.update("delete from profile_titles where id = ?", id) == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That title doesn't exist.");
        return Map.of("deleted", id);
    }

    public record Give(Long userId, Integer days) {
    }

    @PostMapping("/{id:[0-9]+}/grant")
    public Map<String, Object> grant(@PathVariable Long id, @RequestBody Give g) {
        if (g.userId() == null || jdbc.queryForObject("select count(*) from users where id = ?", Long.class, g.userId()) == 0) throw bad("Choose a user.");
        int days = g.days() == null ? 0 : g.days();
        if (days < 0 || days > 3650) throw bad("Days must be 0 (for ever) to 3650.");
        titles.grant(g.userId(), id, "ADMIN", days);
        return Map.of("ok", true);
    }

    @DeleteMapping("/{id:[0-9]+}/grant/{userId:[0-9]+}")
    public Map<String, Object> revoke(@PathVariable Long id, @PathVariable Long userId) {
        titles.revoke(userId, id);
        return Map.of("ok", true);
    }

    @GetMapping("/{id:[0-9]+}/holders")
    public List<Map<String, Object>> holders(@PathVariable Long id) {
        return jdbc.queryForList("select u.id as \"userId\", u.full_name as name, t.source, t.acquired_at as \"since\", t.expires_at as \"expiresAt\", (t.worn_pos is not null) as wearing"
                + " from user_titles t join users u on u.id = t.user_id where t.title_id = ? order by t.acquired_at desc limit 200", id);
    }
}
