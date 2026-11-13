package com.panchayat.backend.parivar;

import com.panchayat.backend.admin.media.AdminMediaService;
import com.panchayat.backend.admin.media.MediaUrls;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Easy editing of what is inside a Family Treasure chest: add a gift from the gift catalogue, or upload pictures and animations
 * straight into the chest (each one is filed in the Parivar library under "Chest rewards" and becomes a reward of that chest).
 * Moderators (Admin) can do this; the chest's own numbers (target, pools) stay Super Admin only.
 */
@RestController
@RequestMapping("/api/v1/admin/parivar/treasure")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
public class ParivarTreasureAdminController {

    static final String CATEGORY = "Chest rewards";
    private static final Set<String> KINDS = Set.of("GIFT", "ITEM", "VIP_CARD", "TITLE", "BADGE", "COLLECTIBLE", "PROFILE_TITLE");
    private static final Set<String> RARITIES = Set.of("COMMON", "PREMIUM", "EPIC", "LEGENDARY", "ULTRA_RARE");

    private final JdbcTemplate jdbc;
    private final AdminMediaService media;

    public ParivarTreasureAdminController(JdbcTemplate jdbc, AdminMediaService media) {
        this.jdbc = jdbc;
        this.media = media;
    }

    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }

    private void audit(Authentication auth, String detail) {
        jdbc.update("insert into parivar_config_audit (admin_name, what, detail) values (?, 'TREASURE REWARDS', ?)", auth == null ? null : auth.getName(), detail);
    }

    private void tier(Long id) {
        if (jdbc.queryForObject("select count(*) from treasure_tiers where id = ?", Long.class, id) == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That chest doesn't exist.");
    }

    private static String rarity(String r) {
        String v = r == null || r.isBlank() ? "COMMON" : r.strip().toUpperCase(Locale.ROOT);
        if (!RARITIES.contains(v)) throw bad("Choose a rarity.");
        return v;
    }

    private static int weight(Integer w) {
        int v = w == null ? 10 : w;
        if (v < 1 || v > 1_000_000) throw bad("Chance (weight) must be between 1 and 1,000,000.");
        return v;
    }

    private static int maxPer(Integer m) {
        int v = m == null ? 0 : m;
        if (v < 0 || v > 1000) throw bad("Limit per chest must be between 0 and 1000.");
        return v;
    }

    private static long amount(Long a, long min) {
        long v = a == null ? 1 : a;
        if (v < min || v > 1_000_000_000L) throw bad("Amount must be at least " + min + ".");
        return v;
    }

    /** The "Chest rewards" library category, made the first time it is needed. */
    private long category() {
        List<Long> ids = jdbc.queryForList("select id from parivar_library_categories where lower(name) = lower(?)", Long.class, CATEGORY);
        if (!ids.isEmpty()) return ids.get(0);
        return jdbc.queryForObject("insert into parivar_library_categories (name, sort_order) values (?, (select coalesce(max(sort_order), 0) + 1 from parivar_library_categories)) returning id", Long.class, CATEGORY);
    }

    /** The four chests with their numbers, picture and how many rewards each holds. */
    @GetMapping("/tiers")
    public List<Map<String, Object>> tiers() {
        return jdbc.query("select t.*, (select count(*) from treasure_rewards r where r.tier_id = t.id) as rewards, (select count(*) from treasure_rewards r where r.tier_id = t.id and r.active) as live from treasure_tiers t order by t.sort_order, t.id", (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("code", rs.getString("code"));
            m.put("name", rs.getString("name"));
            m.put("enabled", rs.getBoolean("enabled"));
            m.put("weeklyTarget", rs.getLong("weekly_target"));
            m.put("unlockMinutes", rs.getInt("unlock_minutes"));
            m.put("poolCoins", rs.getLong("pool_coins"));
            m.put("poolDiamonds", rs.getLong("pool_diamonds"));
            m.put("drawsPerMember", rs.getInt("draws_per_member"));
            m.put("eligibleMinHours", rs.getInt("eligible_min_hours"));
            String img = rs.getString("image_url");
            m.put("imageUrl", img == null || img.isBlank() ? null : MediaUrls.link(img));
            m.put("rewards", rs.getLong("rewards"));
            m.put("live", rs.getLong("live"));
            return m;
        });
    }

    public record TierEdit(String name, Boolean enabled, Long weeklyTarget, Integer unlockMinutes, Long poolCoins, Long poolDiamonds, Integer drawsPerMember, Integer eligibleMinHours) {
    }

    /** The chest's own numbers change what Parivars earn, so only a Super Admin saves them. */
    @PutMapping("/tiers/{id:[0-9]+}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Map<String, Object> editTier(@PathVariable Long id, @RequestBody TierEdit e, Authentication auth) {
        if (e.name() != null && (e.name().isBlank() || e.name().length() > 40)) throw bad("Give the chest a name (up to 40 letters).");
        if (e.weeklyTarget() != null && (e.weeklyTarget() < 1 || e.weeklyTarget() > 1_000_000_000_000L)) throw bad("The weekly target must be at least 1.");
        if (e.unlockMinutes() != null && (e.unlockMinutes() < 0 || e.unlockMinutes() > 525600)) throw bad("Unlock time is 0 to 525600 minutes.");
        if (e.poolCoins() != null && (e.poolCoins() < 0 || e.poolCoins() > 1_000_000_000_000L)) throw bad("The coin pool can't be negative.");
        if (e.poolDiamonds() != null && (e.poolDiamonds() < 0 || e.poolDiamonds() > 1_000_000_000_000L)) throw bad("The diamond pool can't be negative.");
        if (e.drawsPerMember() != null && (e.drawsPerMember() < 0 || e.drawsPerMember() > 10)) throw bad("Draws per member is 0 to 10.");
        if (e.eligibleMinHours() != null && (e.eligibleMinHours() < 0 || e.eligibleMinHours() > 8760)) throw bad("Eligible-after hours is 0 to 8760.");
        int n = jdbc.update("update treasure_tiers set name = coalesce(?, name), enabled = coalesce(?, enabled), weekly_target = coalesce(?, weekly_target), unlock_minutes = coalesce(?, unlock_minutes),"
                        + " pool_coins = coalesce(?, pool_coins), pool_diamonds = coalesce(?, pool_diamonds), draws_per_member = coalesce(?, draws_per_member), eligible_min_hours = coalesce(?, eligible_min_hours) where id = ?",
                e.name() == null ? null : e.name().strip(), e.enabled(), e.weeklyTarget(), e.unlockMinutes(), e.poolCoins(), e.poolDiamonds(), e.drawsPerMember(), e.eligibleMinHours(), id);
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That chest doesn't exist.");
        audit(auth, "chest " + id + " settings changed");
        return Map.of("ok", true);
    }

    /** Upload the chest's picture or animation. */
    @PostMapping(value = "/tiers/{id:[0-9]+}/image", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> tierImage(@PathVariable Long id, @RequestParam("file") MultipartFile file, Authentication auth) {
        tier(id);
        var up = media.upload(file, "parivar", AdminMediaService.VISUALS);
        jdbc.update("update treasure_tiers set image_url = ? where id = ?", up.url(), id);
        audit(auth, "chest " + id + " picture changed");
        return Map.of("ok", true, "imageUrl", MediaUrls.link(up.url()));
    }

    /** The chest's rewards, each with the picture to show (a gift's own art, a library file, or the link typed in). */
    @GetMapping("/{tierId:[0-9]+}/rewards")
    public List<Map<String, Object>> rewards(@PathVariable Long tierId) {
        tier(tierId);
        return jdbc.query("select r.*, g.name as gift_name, c.name as lib_name, c.media_url as lib_url, c.media_format as lib_format from treasure_rewards r"
                        + " left join gifts g on r.kind = 'GIFT' and g.id = r.gift_id"
                        + " left join parivar_collectibles c on r.kind = 'COLLECTIBLE' and c.id = r.ref"
                        + " where r.tier_id = ? order by r.active desc, r.weight desc, r.id", (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("kind", rs.getString("kind"));
            m.put("ref", rs.getLong("ref"));
            m.put("giftId", rs.getString("gift_id"));
            m.put("amount", rs.getLong("amount"));
            m.put("rarity", rs.getString("rarity"));
            m.put("weight", rs.getInt("weight"));
            m.put("maxPerChest", rs.getInt("max_per_chest"));
            m.put("label", rs.getString("label"));
            m.put("active", rs.getBoolean("active"));
            String lib = rs.getString("lib_url");
            String own = rs.getString("image_url");
            m.put("imageUrl", lib != null ? MediaUrls.link(lib) : own == null || own.isBlank() ? null : MediaUrls.link(own));
            m.put("format", rs.getString("lib_format"));
            m.put("giftName", rs.getString("gift_name"));
            return m;
        }, tierId);
    }

    public record NewReward(String kind, Long ref, String giftId, Long amount, String rarity, Integer weight, Integer maxPerChest, String label, String imageUrl) {
    }

    /** Add one reward: a gift (GIFT + gift id), or any other kind (ITEM, VIP_CARD, COLLECTIBLE, PROFILE_TITLE, TITLE, BADGE). */
    @PostMapping("/{tierId:[0-9]+}/rewards")
    @Transactional
    public Map<String, Object> add(@PathVariable Long tierId, @RequestBody NewReward b, Authentication auth) {
        tier(tierId);
        String kind = b.kind() == null ? "" : b.kind().strip().toUpperCase(Locale.ROOT);
        if (!KINDS.contains(kind)) throw bad("Choose what kind of reward this is.");
        long ref = b.ref() == null ? 0 : b.ref();
        String giftId = b.giftId() == null || b.giftId().isBlank() ? null : b.giftId().strip();
        String label = b.label() == null ? "" : b.label().strip();
        switch (kind) {
            case "GIFT" -> {
                if (giftId == null) throw bad("Choose a gift.");
                List<String> n = jdbc.queryForList("select name from gifts where id = ?", String.class, giftId);
                if (n.isEmpty()) throw bad("That gift doesn't exist.");
                if (label.isEmpty()) label = n.get(0);
            }
            case "ITEM" -> {
                if (jdbc.queryForObject("select count(*) from reward_rule_items where id = ?", Long.class, ref) == 0) throw bad("That item (ID " + ref + ") doesn't exist in Spend rewards.");
            }
            case "COLLECTIBLE" -> {
                if (jdbc.queryForObject("select count(*) from parivar_collectibles where id = ?", Long.class, ref) == 0) throw bad("That library item (ID " + ref + ") doesn't exist.");
            }
            case "PROFILE_TITLE" -> {
                if (jdbc.queryForObject("select count(*) from profile_titles where id = ?", Long.class, ref) == 0) throw bad("That profile title (ID " + ref + ") doesn't exist.");
            }
            default -> {
            }
        }
        if (kind.equals("ITEM") || kind.equals("COLLECTIBLE") || kind.equals("PROFILE_TITLE")) {
            if (ref < 1) throw bad("Enter the item's ID.");
        } else ref = 0;
        if (label.isEmpty() || label.length() > 60) throw bad("Give the reward a name (up to 60 letters).");
        long amt = amount(b.amount(), kind.equals("VIP_CARD") ? 1 : 0);
        String img = b.imageUrl() == null || b.imageUrl().isBlank() ? null : b.imageUrl().strip();
        if (img != null && img.length() > 500) throw bad("The picture link is too long.");
        Long id = jdbc.queryForObject("insert into treasure_rewards (tier_id, kind, ref, gift_id, amount, rarity, weight, max_per_chest, label, image_url, active) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, true) returning id",
                Long.class, tierId, kind, ref, kind.equals("GIFT") ? giftId : null, amt, rarity(b.rarity()), weight(b.weight()), maxPer(b.maxPerChest()), label, img);
        audit(auth, "chest " + tierId + ": added " + kind + " \"" + label + "\" (reward " + id + ")");
        return Map.of("id", id);
    }

    /** Upload one or many pictures / animations straight into a chest: each is saved in the library and added as a reward. */
    @PostMapping(value = "/{tierId:[0-9]+}/rewards/upload", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public Map<String, Object> upload(@PathVariable Long tierId, @RequestParam("file") List<MultipartFile> files, @RequestParam(value = "label", required = false) String label,
                                      @RequestParam(value = "rarity", defaultValue = "COMMON") String rarity, @RequestParam(value = "weight", required = false) Integer weight,
                                      @RequestParam(value = "maxPerChest", required = false) Integer maxPerChest, Authentication auth) {
        tier(tierId);
        if (files == null || files.isEmpty() || files.size() > 30) throw bad("Choose 1 to 30 files.");
        String r = rarity(rarity);
        int w = weight(weight);
        int mx = maxPer(maxPerChest);
        long cat = category();
        int added = 0;
        for (MultipartFile f : files) {
            String name;
            if (files.size() == 1 && label != null && !label.isBlank()) name = label.strip();
            else {
                String fn = f.getOriginalFilename() == null ? "Reward" : f.getOriginalFilename();
                int dot = fn.lastIndexOf('.');
                name = (dot > 0 ? fn.substring(0, dot) : fn).replace('_', ' ').replace('-', ' ').strip();
            }
            if (name.isEmpty()) name = "Reward";
            if (name.length() > 60) name = name.substring(0, 60);
            var up = media.upload(f, "parivar", AdminMediaService.VISUALS);
            Long itemId = jdbc.queryForObject("insert into parivar_collectibles (kind, category_id, name, media_url, media_format, rarity) values ('ITEM', ?, ?, ?, ?, ?) returning id", Long.class, cat, name, up.url(), up.format(), r);
            jdbc.update("insert into treasure_rewards (tier_id, kind, ref, amount, rarity, weight, max_per_chest, label, active) values (?, 'COLLECTIBLE', ?, 1, ?, ?, ?, ?, true)", tierId, itemId, r, w, mx, name);
            added++;
        }
        audit(auth, "chest " + tierId + ": " + added + " uploaded reward(s)");
        return Map.of("ok", true, "added", added);
    }

    public record EditReward(String label, String rarity, Integer weight, Integer maxPerChest, Long amount, Boolean active) {
    }

    @PutMapping("/rewards/{id:[0-9]+}")
    public Map<String, Object> edit(@PathVariable Long id, @RequestBody EditReward e, Authentication auth) {
        if (e.label() != null && (e.label().isBlank() || e.label().length() > 60)) throw bad("Give the reward a name (up to 60 letters).");
        int n = jdbc.update("update treasure_rewards set label = coalesce(?, label), rarity = coalesce(?, rarity), weight = coalesce(?, weight), max_per_chest = coalesce(?, max_per_chest),"
                        + " amount = coalesce(?, amount), active = coalesce(?, active) where id = ?",
                e.label() == null ? null : e.label().strip(), e.rarity() == null ? null : rarity(e.rarity()), e.weight() == null ? null : weight(e.weight()),
                e.maxPerChest() == null ? null : maxPer(e.maxPerChest()), e.amount() == null ? null : amount(e.amount(), 0), e.active(), id);
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That reward doesn't exist.");
        audit(auth, "reward " + id + " changed");
        return Map.of("ok", true);
    }

    @DeleteMapping("/rewards/{id:[0-9]+}")
    public Map<String, Object> delete(@PathVariable Long id, Authentication auth) {
        if (jdbc.update("delete from treasure_rewards where id = ?", id) == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That reward doesn't exist.");
        // People who already won it keep it; its picture stays in the library
        audit(auth, "reward " + id + " removed");
        return Map.of("deleted", id);
    }
}
