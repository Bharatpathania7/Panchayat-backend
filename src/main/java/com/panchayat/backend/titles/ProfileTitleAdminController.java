package com.panchayat.backend.titles;

import com.panchayat.backend.admin.media.AdminMediaService;
import com.panchayat.backend.admin.media.MediaUrls;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * The admin makes the profile titles (colours, picture or animation, price, free, how long they last, who may buy them and when), gives or takes them,
 * sets automatic rules (a list of IDs, a VIP level, a Parivar role) and decides how many titles a person can wear.
 */
@RestController
@RequestMapping("/api/v1/admin/titles")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
public class ProfileTitleAdminController {

    private static final Pattern COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private static final Set<String> RARITIES = Set.of("COMMON", "PREMIUM", "EPIC", "LEGENDARY", "ULTRA_RARE");
    private static final Set<String> RULE_KINDS = Set.of("IDS", "VIP_LEVEL", "PARIVAR_ROLE");

    private final JdbcTemplate jdbc;
    private final AdminMediaService media;
    private final ProfileTitleService titles;
    private final TitleRuleService rules;

    public ProfileTitleAdminController(JdbcTemplate jdbc, AdminMediaService media, ProfileTitleService titles, TitleRuleService rules) {
        this.jdbc = jdbc;
        this.media = media;
        this.titles = titles;
        this.rules = rules;
    }

    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }

    private static String iso(Timestamp t) {
        return t == null ? null : t.toInstant().toString();
    }

    @GetMapping
    public Map<String, Object> list() {
        List<Map<String, Object>> rows = jdbc.query("select t.*, (select count(*) from user_titles u where u.title_id = t.id and (u.expires_at is null or u.expires_at > now())) as holders,"
                        + " (select count(*) from user_titles u where u.title_id = t.id and u.worn_pos is not null) as wearing,"
                        + " (select count(*) from title_auto_rules r where r.title_id = t.id and r.active) as rule_count from profile_titles t order by t.sort_order, t.id",
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
                    m.put("durationDays", rs.getInt("duration_days"));
                    m.put("forSale", rs.getBoolean("for_sale"));
                    m.put("saleFrom", iso(rs.getTimestamp("sale_from")));
                    m.put("saleUntil", iso(rs.getTimestamp("sale_until")));
                    m.put("minVipLevel", rs.getInt("min_vip_level"));
                    m.put("parivarOnly", rs.getBoolean("parivar_only"));
                    m.put("holders", rs.getLong("holders"));
                    m.put("wearing", rs.getLong("wearing"));
                    m.put("rules", rs.getLong("rule_count"));
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

    private void check(String name, String from, String to, String text, String rarity, long price, int duration, int minVip) {
        if (name == null || name.isBlank() || name.strip().length() > 30) throw bad("Give the title a name (up to 30 letters).");
        for (String c : new String[]{from, to, text}) if (c == null || !COLOR.matcher(c).matches()) throw bad("Colours must look like #7b5cff.");
        if (!RARITIES.contains(rarity)) throw bad("Choose a rarity.");
        if (price < 0 || price > 1_000_000_000L) throw bad("The price must be 0 or more.");
        if (duration < 0 || duration > 36500) throw bad("Days must be 0 (for ever) to 36500.");
        if (minVip < 0 || minVip > 20) throw bad("The VIP level must be 0 to 20.");
    }

    private static Timestamp when(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Timestamp.from(Instant.parse(s.length() == 10 ? s + "T00:00:00Z" : s));
        } catch (Exception e) {
            throw bad("Use a date like 2026-12-31.");
        }
    }

    @PostMapping(consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> create(@RequestParam("name") String name, @RequestParam(value = "colorFrom", defaultValue = "#7b5cff") String from, @RequestParam(value = "colorTo", defaultValue = "#c56bff") String to,
                                      @RequestParam(value = "textColor", defaultValue = "#ffffff") String text, @RequestParam(value = "rarity", defaultValue = "COMMON") String rarity,
                                      @RequestParam(value = "priceCoins", defaultValue = "0") long price, @RequestParam(value = "free", defaultValue = "false") boolean free,
                                      @RequestParam(value = "durationDays", defaultValue = "0") int duration, @RequestParam(value = "minVipLevel", defaultValue = "0") int minVip,
                                      @RequestParam(value = "parivarOnly", defaultValue = "false") boolean parivarOnly,
                                      @RequestParam(value = "file", required = false) MultipartFile file) {
        rarity = rarity.toUpperCase(Locale.ROOT);
        check(name, from, to, text, rarity, price, duration, minVip);
        String icon = file == null || file.isEmpty() ? null : media.upload(file, "titles", AdminMediaService.VISUALS).url();
        Long id = jdbc.queryForObject("insert into profile_titles (name, icon_url, color_from, color_to, text_color, rarity, price_coins, free, duration_days, min_vip_level, parivar_only, sort_order)"
                        + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, (select coalesce(max(sort_order), 0) + 1 from profile_titles)) returning id", Long.class,
                name.strip(), icon, from, to, text, rarity, price, free, duration, minVip, parivarOnly);
        return Map.of("id", id);
    }

    public record Edit(String name, String colorFrom, String colorTo, String textColor, String rarity, Long priceCoins, Boolean free, Boolean active, Integer sortOrder,
                       Integer durationDays, Boolean forSale, String saleFrom, String saleUntil, Boolean clearSaleWindow, Integer minVipLevel, Boolean parivarOnly) {
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
        int duration = e.durationDays() != null ? e.durationDays() : ((Number) c.get("duration_days")).intValue();
        int minVip = e.minVipLevel() != null ? e.minVipLevel() : ((Number) c.get("min_vip_level")).intValue();
        check(name, from, to, text, rarity, price, duration, minVip);
        Timestamp saleFrom = Boolean.TRUE.equals(e.clearSaleWindow()) ? null : e.saleFrom() != null ? when(e.saleFrom()) : (Timestamp) c.get("sale_from");
        Timestamp saleUntil = Boolean.TRUE.equals(e.clearSaleWindow()) ? null : e.saleUntil() != null ? when(e.saleUntil()) : (Timestamp) c.get("sale_until");
        if (saleFrom != null && saleUntil != null && saleUntil.before(saleFrom)) throw bad("The sale must end after it starts.");
        jdbc.update("update profile_titles set name = ?, color_from = ?, color_to = ?, text_color = ?, rarity = ?, price_coins = ?, free = ?, active = ?, sort_order = ?, duration_days = ?, for_sale = ?,"
                        + " sale_from = ?, sale_until = ?, min_vip_level = ?, parivar_only = ? where id = ?",
                name.strip(), from, to, text, rarity, price, e.free() != null ? e.free() : c.get("free"), e.active() != null ? e.active() : c.get("active"),
                e.sortOrder() != null ? e.sortOrder() : c.get("sort_order"), duration, e.forSale() != null ? e.forSale() : c.get("for_sale"), saleFrom, saleUntil, minVip,
                e.parivarOnly() != null ? e.parivarOnly() : c.get("parivar_only"), id);
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

    /* ---------------- giving ---------------- */

    public record Give(Long userId, Integer days) {
    }

    @PostMapping("/{id:[0-9]+}/grant")
    public Map<String, Object> grant(@PathVariable Long id, @RequestBody Give g) {
        if (g.userId() == null || jdbc.queryForObject("select count(*) from users where id = ?", Long.class, g.userId()) == 0) throw bad("Choose a user.");
        int days = g.days() == null ? 0 : g.days();
        if (days < 0 || days > 36500) throw bad("Days must be 0 (the title's own time) up to 36500.");
        titles.grantUsual(g.userId(), id, "ADMIN", days);
        return Map.of("ok", true);
    }

    public record GiveIds(String ids, Integer days) {
    }

    /** Gives the title to a list of 7 digit user IDs at once (the rare people, the team ...). Days = 0 uses the title's own time. */
    @PostMapping("/{id:[0-9]+}/grant-ids")
    public Map<String, Object> grantIds(@PathVariable Long id, @RequestBody GiveIds g) {
        String clean = rules.cleanValue("IDS", g.ids());
        int days = g.days() == null ? 0 : g.days();
        if (days < 0 || days > 36500) throw bad("Days must be 0 (the title's own time) up to 36500.");
        int given = 0;
        List<String> missing = new ArrayList<>();
        for (String pub : clean.split(",")) {
            List<Long> u = jdbc.queryForList("select id from users where public_id = ?", Long.class, Long.valueOf(pub));
            if (u.isEmpty()) {
                missing.add(pub);
                continue;
            }
            titles.grantUsual(u.get(0), id, "ADMIN", days);
            given++;
        }
        return Map.of("given", given, "notFound", missing);
    }

    @DeleteMapping("/{id:[0-9]+}/grant/{userId:[0-9]+}")
    public Map<String, Object> revoke(@PathVariable Long id, @PathVariable Long userId) {
        titles.revoke(userId, id);
        return Map.of("ok", true);
    }

    @GetMapping("/{id:[0-9]+}/holders")
    public List<Map<String, Object>> holders(@PathVariable Long id) {
        return jdbc.queryForList("select u.id as \"userId\", u.public_id as \"publicId\", u.full_name as name, t.source, t.acquired_at as \"since\", t.expires_at as \"expiresAt\", (t.worn_pos is not null) as wearing"
                + " from user_titles t join users u on u.id = t.user_id where t.title_id = ? and (t.expires_at is null or t.expires_at > now()) order by t.acquired_at desc limit 200", id);
    }

    /* ---------------- automatic rules ---------------- */

    @GetMapping("/{id:[0-9]+}/rules")
    public List<Map<String, Object>> rulesOf(@PathVariable Long id) {
        return jdbc.queryForList("select id, kind, value, days, active, matched, last_run as \"lastRun\" from title_auto_rules where title_id = ? order by id", id);
    }

    public record RuleBody(String kind, String value, Integer days, Boolean active) {
    }

    @PostMapping("/{id:[0-9]+}/rules")
    public Map<String, Object> addRule(@PathVariable Long id, @RequestBody RuleBody b) {
        if (jdbc.queryForObject("select count(*) from profile_titles where id = ?", Long.class, id) == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That title doesn't exist.");
        String kind = b.kind() == null ? "" : b.kind().toUpperCase(Locale.ROOT);
        if (!RULE_KINDS.contains(kind)) throw bad("Choose a rule type.");
        String value = rules.cleanValue(kind, b.value());
        int days = b.days() == null ? 0 : b.days();
        if (days < 0 || days > 36500) throw bad("Days must be 0 to 36500.");
        Long rid = jdbc.queryForObject("insert into title_auto_rules (title_id, kind, value, days) values (?, ?, ?, ?) returning id", Long.class, id, kind, value, days);
        rules.sync(rid);
        return Map.of("id", rid);
    }

    @PutMapping("/rules/{rid:[0-9]+}")
    public Map<String, Object> editRule(@PathVariable Long rid, @RequestBody RuleBody b) {
        List<Map<String, Object>> cur = jdbc.queryForList("select * from title_auto_rules where id = ?", rid);
        if (cur.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That rule doesn't exist.");
        String kind = (String) cur.get(0).get("kind");
        String value = b.value() == null ? (String) cur.get(0).get("value") : rules.cleanValue(kind, b.value());
        int days = b.days() == null ? ((Number) cur.get(0).get("days")).intValue() : b.days();
        if (days < 0 || days > 36500) throw bad("Days must be 0 to 36500.");
        boolean active = b.active() == null ? Boolean.TRUE.equals(cur.get(0).get("active")) : b.active();
        jdbc.update("update title_auto_rules set value = ?, days = ?, active = ? where id = ?", value, days, active, rid);
        if (active) rules.sync(rid);
        return Map.of("ok", true);
    }

    /** Deleting a rule that holds the title only while people qualify takes the title back from them. */
    @DeleteMapping("/rules/{rid:[0-9]+}")
    public Map<String, Object> deleteRule(@PathVariable Long rid, @RequestParam(defaultValue = "false") boolean keep) {
        List<Map<String, Object>> cur = jdbc.queryForList("select title_id, days from title_auto_rules where id = ?", rid);
        if (cur.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That rule doesn't exist.");
        if (!keep && ((Number) cur.get(0).get("days")).intValue() == 0) jdbc.update("delete from user_titles where title_id = ? and source = ?", cur.get(0).get("title_id"), "AUTO:" + rid);
        jdbc.update("delete from title_auto_rules where id = ?", rid);
        return Map.of("deleted", rid);
    }

    @PostMapping("/rules/sync")
    public Map<String, Object> syncNow() {
        Map<Long, Integer> r = rules.sync(null);
        return Map.of("rules", r.size(), "matched", r.values().stream().mapToInt(Integer::intValue).sum());
    }
}
