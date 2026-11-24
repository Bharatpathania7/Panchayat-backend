package com.panchayat.backend.admin.inventory;

import com.panchayat.backend.admin.media.MediaUrls;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Inventory: every item the app hands out, in one list, with WHERE each one goes, and an on / off switch.
 *   GET   /api/v1/admin/inventory?kind=&q=        kinds: GIFT, SHOP, FRAME, TITLE, SPEND_REWARD, FAMILY_CHEST, GACHA, ROOM_TREASURE
 *   PATCH /api/v1/admin/inventory/{kind}/{id}   { active: true | false }
 * Each row: kind, id, name, picture, where (the places people get or see it), price / amount, active, and the admin
 * page to edit it fully (page). Nothing here changes how items work: the switch is the same "active" those pages use.
 */
@RestController
@RequestMapping("/api/v1/admin/inventory")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
public class AdminInventoryController {

    private final JdbcTemplate jdbc;

    public AdminInventoryController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** kind -> table, active column, admin page. */
    private record Kind(String table, String activeCol, String page) {
    }

    private static final Map<String, Kind> KINDS = new LinkedHashMap<>();

    static {
        // page = the admin panel page that edits it fully
        KINDS.put("GIFT", new Kind("gifts", "active", "gifts"));
        KINDS.put("SHOP", new Kind("shop_products", "active", "shop"));
        KINDS.put("FRAME", new Kind("name_frames", "active", "nameFrames"));
        KINDS.put("TITLE", new Kind("profile_titles", "active", "titles"));
        KINDS.put("SPEND_REWARD", new Kind("reward_rule_items", "enabled", "rewards"));
        KINDS.put("FAMILY_CHEST", new Kind("parivar_chest_rewards", "active", "parivar"));
        KINDS.put("GACHA", new Kind("parivar_gacha_rewards", "active", "parivar"));
        KINDS.put("ROOM_TREASURE", new Kind("treasure_rewards", "active", "roomTreasure"));
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String kind, @RequestParam(required = false) String q) {
        String only = kind == null || kind.isBlank() ? null : kind.strip().toUpperCase(Locale.ROOT);
        if (only != null && !KINDS.containsKey(only)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown kind");
        String text = q == null ? "" : q.strip().toLowerCase(Locale.ROOT);
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String k : KINDS.keySet()) {
            List<Map<String, Object>> rows;
            try {
                rows = rows(k);
            } catch (Exception e) {
                rows = List.of(); // a table this database doesn't have yet
            }
            counts.put(k, rows.size());
            if (only != null && !only.equals(k)) continue;
            for (Map<String, Object> r : rows) {
                if (!text.isEmpty() && !String.valueOf(r.get("name")).toLowerCase(Locale.ROOT).contains(text)) continue;
                items.add(r);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("counts", counts);
        out.put("items", items.size() > 1000 ? items.subList(0, 1000) : items);
        return out;
    }

    private Map<String, Object> row(String kind, Object id, Object name, String picture, List<String> where, Object value, Object active) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("id", String.valueOf(id));
        m.put("name", name == null ? "(no name)" : String.valueOf(name));
        m.put("picture", picture == null || picture.isBlank() ? null : (picture.startsWith("/frames/") ? picture : MediaUrls.link(picture)));
        m.put("where", where);
        m.put("value", value);
        m.put("active", Boolean.TRUE.equals(active));
        m.put("page", KINDS.get(kind).page());
        return m;
    }

    private List<Map<String, Object>> rows(String k) {
        return switch (k) {
            case "GIFT" -> jdbc.query("select id, name, price, category, active, image_url, announce from gifts order by sort_order, name", (rs, i) -> {
                List<String> w = new ArrayList<>(List.of("Gift panel in rooms (" + rs.getString("category") + ")", "Private chat gifts"));
                if (rs.getBoolean("announce")) w.add("Server notification when sent");
                return row(k, rs.getString("id"), rs.getString("name"), rs.getString("image_url"), w, rs.getLong("price") + " coins", rs.getBoolean("active"));
            });
            case "SHOP" -> jdbc.query("select p.id, p.name, p.price, p.asset_url, p.active, p.wear_slot, p.valid_days, c.name as cat, c.currency from shop_products p left join shop_categories c on c.id = p.category_id order by c.display_order, p.display_order, p.id", (rs, i) -> {
                List<String> w = new ArrayList<>();
                w.add("Shop > " + (rs.getString("cat") == null ? "no category" : rs.getString("cat")) + ("PK_COINS".equalsIgnoreCase(rs.getString("currency")) ? " (PK Coins)" : ""));
                w.add("Bag after buying" + (rs.getObject("valid_days") == null ? "" : " (" + rs.getInt("valid_days") + " days)"));
                if (rs.getString("wear_slot") != null) w.add("Worn as " + rs.getString("wear_slot").toLowerCase(Locale.ROOT));
                return row(k, rs.getLong("id"), rs.getString("name"), rs.getString("asset_url"), w, rs.getLong("price") + ("PK_COINS".equalsIgnoreCase(rs.getString("currency")) ? " PK coins" : " coins"), rs.getBoolean("active"));
            });
            case "FRAME" -> jdbc.query("select f.id, f.name, f.image_url, f.active, (select count(*) from name_frame_rules r where r.frame_id = f.id and r.active) as rules,"
                    + " exists (select 1 from royal_pack_settings s where s.frame_id = f.id) as royal from name_frames f order by f.sort_order, f.id", (rs, i) -> {
                List<String> w = new ArrayList<>(List.of("Seats and profile (name frame)", "Bag"));
                if (rs.getLong("rules") > 0) w.add("Given by top-up amount rules");
                if (rs.getBoolean("royal")) w.add("Royal Pack (Munshi ji)");
                return row(k, rs.getLong("id"), rs.getString("name"), rs.getString("image_url"), w, null, rs.getBoolean("active"));
            });
            case "TITLE" -> jdbc.query("select t.id, t.name, t.icon_url, t.active, t.free, t.for_sale, t.price_coins, t.duration_days,"
                    + " exists (select 1 from royal_pack_settings s where t.id in (s.nawab_title_id, s.king_title_id, s.queen_title_id)) as royal,"
                    + " exists (select 1 from coin_pack_titles c where c.title_id = t.id) as pack from profile_titles t order by t.sort_order, t.id", (rs, i) -> {
                List<String> w = new ArrayList<>(List.of("Profile titles"));
                if (rs.getBoolean("free")) w.add("Free for everyone");
                else if (rs.getBoolean("for_sale") && rs.getLong("price_coins") > 0) w.add("Titles store");
                if (rs.getBoolean("pack")) w.add("Coin pack bonus");
                if (rs.getBoolean("royal")) w.add("Royal Pack (Munshi ji)");
                return row(k, rs.getLong("id"), rs.getString("name"), rs.getString("icon_url"), w,
                        rs.getLong("price_coins") > 0 ? rs.getLong("price_coins") + " coins" : null, rs.getBoolean("active"));
            });
            case "SPEND_REWARD" -> jdbc.query("select i.id, i.name, i.item_type, i.media_url, i.enabled, i.duration_days, r.name as rule, r.coin_threshold from reward_rule_items i join reward_rules r on r.id = i.rule_id order by r.coin_threshold, i.priority, i.id", (rs, i) -> row(k, rs.getLong("id"), rs.getString("name"), rs.getString("media_url"),
                    List.of("Spend reward \"" + rs.getString("rule") + "\" at " + rs.getLong("coin_threshold") + " coins", "Bag (" + rs.getString("item_type").toLowerCase(Locale.ROOT) + ")"),
                    rs.getObject("duration_days") == null ? null : rs.getInt("duration_days") + " days", rs.getBoolean("enabled")));
            case "FAMILY_CHEST" -> jdbc.query("select id, label, kind, amount, active from parivar_chest_rewards order by chest_id, id", (rs, i) -> row(k, rs.getLong("id"), rs.getString("label"), null,
                    List.of("Family treasure chest", "Bag / wallet of the winner (" + rs.getString("kind").toLowerCase(Locale.ROOT) + ")"), rs.getLong("amount"), rs.getBoolean("active")));
            case "GACHA" -> jdbc.query("select id, label, kind, amount, image_url, active from parivar_gacha_rewards order by machine_id, id", (rs, i) -> row(k, rs.getLong("id"), rs.getString("label"), rs.getString("image_url"),
                    List.of("Family Gacha machine", "Bag / wallet of the winner (" + rs.getString("kind").toLowerCase(Locale.ROOT) + ")"), rs.getLong("amount"), rs.getBoolean("active")));
            case "ROOM_TREASURE" -> jdbc.query("select id, label, kind, amount, image_url, active from treasure_rewards order by tier_id, id", (rs, i) -> row(k, rs.getLong("id"), rs.getString("label"), rs.getString("image_url"),
                    List.of("Room treasure chests", "Bag / wallet of the winner (" + rs.getString("kind").toLowerCase(Locale.ROOT) + ")"), rs.getLong("amount"), rs.getBoolean("active")));
            default -> List.of();
        };
    }

    public record ActiveBody(Boolean active) {
    }

    /** Switch one item on or off (the same switch its own admin page has). */
    @PatchMapping("/{kind}/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Map<String, Object> setActive(@PathVariable String kind, @PathVariable String id, @RequestBody ActiveBody body) {
        Kind k = KINDS.get(kind.toUpperCase(Locale.ROOT));
        if (k == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown kind");
        if (body == null || body.active() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Say on or off.");
        // Gifts have text ids; everything else is a number
        int n = "gifts".equals(k.table())
                ? jdbc.update("update gifts set active = ? where id = ?", body.active(), id)
                : jdbc.update("update " + k.table() + " set " + k.activeCol() + " = ? where id = ?", body.active(), Long.parseLong(id));
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Item not found");
        return Map.of("id", id, "active", body.active());
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> refused(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("message", e.getReason() == null ? "That didn't work." : e.getReason()));
    }
}
