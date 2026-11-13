package com.panchayat.backend.parivar;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * The rest of the Parivar admin panel. Every list that makes up the Parivar business (Kavach types, cheer, Gacha machines and rewards,
 * chests and rewards, shop, tasks, ranking prizes) is edited through one validated endpoint, plus the extra settings (Chori rules,
 * feature switches, limits) and read-only transaction logs. Admins can look; only Super Admins save. Every save is written to the change log.
 */
@RestController
@RequestMapping("/api/v1/admin/parivar")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
public class ParivarConfigAdminController {

    private enum T { STR, INT, LONG, BOOL, ENUM }

    private record Col(String key, String column, T type, long min, long max, boolean required, List<String> values) {
        static Col str(String key, String column, int max, boolean required) { return new Col(key, column, T.STR, 0, max, required, null); }
        static Col num(String key, String column, long min, long max) { return new Col(key, column, T.LONG, min, max, true, null); }
        static Col integer(String key, String column, long min, long max) { return new Col(key, column, T.INT, min, max, true, null); }
        static Col bool(String key, String column) { return new Col(key, column, T.BOOL, 0, 1, true, null); }
        static Col oneOf(String key, String column, String... v) { return new Col(key, column, T.ENUM, 0, 0, true, List.of(v)); }
    }

    private record Section(String table, String order, List<Col> cols, boolean deletable, String filterKey) {
    }

    private static final List<String> KINDS = List.of("COINS", "FUNDS", "CHARM", "CHEST", "CARD", "BARRIER");
    private static final long BIG = 1_000_000_000_000L;

    private static final Map<String, Section> SECTIONS = new LinkedHashMap<>();

    static {
        SECTIONS.put("barriers", new Section("parivar_barrier_types", "sort_order, id", List.of(
                Col.str("name", "name", 40, true), Col.str("imageUrl", "image_url", 500, false), Col.integer("strength", "strength", 0, 100), Col.integer("durationMinutes", "duration_minutes", 1, 525600),
                Col.num("priceFunds", "price_funds", 0, BIG), Col.integer("cooldownMinutes", "cooldown_minutes", 0, 525600), Col.integer("dailyLimit", "daily_limit", 0, 1000),
                Col.integer("minLevel", "min_level", 1, 100), Col.integer("sortOrder", "sort_order", 0, 100000), Col.bool("active", "active")), false, null));
        SECTIONS.put("cheer", new Section("parivar_cheer_tiers", "sort_order, id", List.of(
                Col.str("name", "name", 30, true), Col.integer("times", "times", 1, 100000), Col.num("coinsCost", "coins_cost", 0, BIG), Col.num("expGain", "exp_gain", 0, BIG),
                Col.num("fundsGain", "funds_gain", 0, BIG), Col.integer("dailyLimit", "daily_limit", 0, 100000), Col.integer("sortOrder", "sort_order", 0, 100000), Col.bool("active", "active")), false, null));
        SECTIONS.put("gacha-machines", new Section("parivar_gacha_machines", "sort_order, id", List.of(
                Col.str("name", "name", 40, true), Col.str("tier", "tier", 12, true), Col.str("imageUrl", "image_url", 500, false), Col.str("animationUrl", "animation_url", 500, false),
                Col.num("priceFunds", "price_funds", 0, BIG), Col.num("serviceFeeCoins", "service_fee_coins", 0, BIG), Col.integer("freePerDay", "free_per_day", 0, 1000),
                Col.num("freeFundCost", "free_fund_cost", 0, BIG), Col.num("rollPriceCoins", "roll_price_coins", 0, BIG),
                Col.integer("minLevel", "min_level", 1, 100), Col.integer("sortOrder", "sort_order", 0, 100000), Col.bool("active", "active")), false, null));
        SECTIONS.put("gacha-rewards", new Section("parivar_gacha_rewards", "machine_id, weight desc, id", List.of(
                Col.num("machineId", "machine_id", 1, BIG), Col.oneOf("kind", "kind", "COINS", "FUNDS", "CHARM", "CHEST", "CARD", "BARRIER", "GIFT_CARD", "WEAPON", "ITEM"), Col.num("ref", "ref", 0, BIG), Col.str("giftId", "gift_id", 64, false), Col.num("amount", "amount", 1, BIG),
                Col.integer("weight", "weight", 1, 1_000_000), Col.oneOf("rarity", "rarity", "COMMON", "RARE", "EPIC", "LEGENDARY"), Col.str("label", "label", 60, true),
                Col.str("imageUrl", "image_url", 500, false), Col.bool("active", "active")), true, "machineId"));
        SECTIONS.put("chests", new Section("parivar_chest_types", "sort_order, id", List.of(
                Col.str("name", "name", 40, true), Col.str("imageUrl", "image_url", 500, false), Col.num("priceFunds", "price_funds", 0, BIG), Col.integer("pieces", "pieces", 1, 1000),
                Col.integer("minLevel", "min_level", 1, 100), Col.integer("sortOrder", "sort_order", 0, 100000), Col.bool("active", "active")), false, null));
        SECTIONS.put("chest-rewards", new Section("parivar_chest_rewards", "chest_id, weight desc, id", List.of(
                Col.num("chestId", "chest_id", 1, BIG), Col.oneOf("kind", "kind", "COINS", "FUNDS", "CHARM", "CHEST", "CARD", "BARRIER"), Col.num("ref", "ref", 0, BIG), Col.num("amount", "amount", 1, BIG),
                Col.integer("weight", "weight", 1, 1_000_000), Col.str("label", "label", 60, true), Col.bool("active", "active")), true, "chestId"));
        SECTIONS.put("treasure-tiers", new Section("treasure_tiers", "sort_order, id", List.of(
                Col.str("code", "code", 12, true), Col.str("name", "name", 40, true), Col.bool("enabled", "enabled"), Col.num("weeklyTarget", "weekly_target", 1, BIG),
                Col.integer("unlockMinutes", "unlock_minutes", 0, 525600), Col.num("poolCoins", "pool_coins", 0, BIG), Col.num("poolDiamonds", "pool_diamonds", 0, BIG),
                Col.integer("drawsPerMember", "draws_per_member", 0, 10), Col.integer("eligibleMinHours", "eligible_min_hours", 0, 8760), Col.str("imageUrl", "image_url", 500, false),
                Col.integer("sortOrder", "sort_order", 0, 100000)), false, null));
        SECTIONS.put("treasure-rewards", new Section("treasure_rewards", "tier_id, weight desc, id", List.of(
                Col.num("tierId", "tier_id", 1, BIG), Col.oneOf("kind", "kind", "GIFT", "ITEM", "VIP_CARD", "TITLE", "BADGE", "COLLECTIBLE", "PROFILE_TITLE"), Col.num("ref", "ref", 0, BIG), Col.str("giftId", "gift_id", 64, false),
                Col.num("amount", "amount", 0, BIG), Col.oneOf("rarity", "rarity", "COMMON", "PREMIUM", "EPIC", "LEGENDARY", "ULTRA_RARE"), Col.integer("weight", "weight", 1, 1_000_000),
                Col.integer("maxPerChest", "max_per_chest", 0, 1000), Col.str("label", "label", 60, true), Col.str("imageUrl", "image_url", 500, false), Col.bool("active", "active")), true, "tierId"));
        SECTIONS.put("shop", new Section("parivar_shop_items", "sort_order, id", List.of(
                Col.str("name", "name", 40, true), Col.str("imageUrl", "image_url", 500, false), Col.oneOf("kind", "kind", "GACHA_CARD", "CHEST", "BARRIER"), Col.num("ref", "ref", 1, BIG),
                Col.integer("qty", "qty", 1, 1000), Col.num("priceFunds", "price_funds", 0, BIG), Col.num("priceCoins", "price_coins", 0, BIG), Col.integer("dailyLimit", "daily_limit", 0, 100000),
                Col.integer("minLevel", "min_level", 1, 100), Col.integer("sortOrder", "sort_order", 0, 100000), Col.bool("active", "active")), false, null));
        SECTIONS.put("tasks", new Section("parivar_tasks", "period, sort_order, id", List.of(
                Col.str("title", "title", 60, true), Col.oneOf("period", "period", "DAILY", "WEEKLY"), Col.oneOf("metric", "metric", "GIFT_EXP", "DONATED_COINS", "CHEERS", "CHECKINS", "ACTIVITY_EXP", "GACHA_ROLLS"),
                Col.num("target", "target", 1, BIG), Col.oneOf("rewardKind", "reward_kind", "EXP", "FUNDS", "COINS", "CHARM"), Col.num("rewardAmount", "reward_amount", 1, BIG),
                Col.integer("sortOrder", "sort_order", 0, 100000), Col.bool("active", "active")), true, null));
        SECTIONS.put("rank-rewards", new Section("parivar_rank_rewards", "rank_from, id", List.of(
                Col.integer("rankFrom", "rank_from", 1, 1000), Col.integer("rankTo", "rank_to", 1, 1000), Col.num("funds", "funds", 0, BIG), Col.bool("active", "active")), true, null));
    }

    /** The extra settings: column, type and limits. */
    private static final Map<String, Col> EXTRA = new LinkedHashMap<>();

    static {
        for (Col c : List.of(Col.bool("cheerEnabled", "cheer_enabled"), Col.bool("stealEnabled", "steal_enabled"), Col.integer("stealMinLevel", "steal_min_level", 1, 100),
                Col.integer("stealDailyAttempts", "steal_daily_attempts", 0, 1000), Col.integer("stealCooldownMinutes", "steal_cooldown_minutes", 0, 100000),
                Col.integer("stealSuccessPercent", "steal_success_percent", 0, 100), Col.num("stealTakePercent", "steal_take_percent", 0, 100), Col.num("stealMaxTake", "steal_max_take", 0, BIG),
                Col.num("stealFailPenalty", "steal_fail_penalty", 0, BIG), Col.num("stealMinTargetFunds", "steal_min_target_funds", 0, BIG), Col.bool("gachaEnabled", "gacha_enabled"),
                Col.bool("shopEnabled", "shop_enabled"), Col.bool("tasksEnabled", "tasks_enabled"), Col.bool("chatEnabled", "chat_enabled"), Col.bool("voiceEnabled", "voice_enabled"),
                Col.integer("voiceMinLevel", "voice_min_level", 1, 100), Col.integer("chestExpiryHours", "chest_expiry_hours", 1, 720), Col.oneOf("chestExpiryPolicy", "chest_expiry_policy", "NONE", "REFUND_FUNDS"),
                Col.integer("checkinExp", "checkin_exp", 0, 1_000_000), Col.num("gachaTicketPrice", "gacha_ticket_price", 0, 1_000_000), Col.bool("rankRewardsEnabled", "rank_rewards_enabled")))
            EXTRA.put(c.key(), c);
    }

    private final JdbcTemplate jdbc;

    public ParivarConfigAdminController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @ExceptionHandler(ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<Map<String, String>> handle(ResponseStatusException e) {
        return org.springframework.http.ResponseEntity.status(e.getStatusCode()).body(Map.of("message", e.getReason() == null ? "That didn't work" : e.getReason()));
    }

    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }

    private void audit(Authentication auth, String what, String detail) {
        jdbc.update("insert into parivar_config_audit (admin_name, what, detail) values (?, ?, ?)", auth == null ? null : auth.getName(), what, detail);
    }

    private static String camel(String snake) {
        StringBuilder b = new StringBuilder();
        boolean up = false;
        for (char ch : snake.toCharArray()) {
            if (ch == '_') up = true;
            else {
                b.append(up ? Character.toUpperCase(ch) : ch);
                up = false;
            }
        }
        return b.toString();
    }

    private Object coerce(Col c, Object v) {
        if (v == null || (v instanceof String s && s.isBlank())) {
            if (c.type() == T.STR && !c.required()) return null;
            throw bad("\"" + c.key() + "\" is required.");
        }
        switch (c.type()) {
            case BOOL -> {
                return Boolean.valueOf(String.valueOf(v));
            }
            case STR -> {
                String s = String.valueOf(v).strip();
                if (s.length() > c.max()) throw bad("\"" + c.key() + "\" can be up to " + c.max() + " letters.");
                if (s.isEmpty() && c.required()) throw bad("\"" + c.key() + "\" is required.");
                return s.isEmpty() ? null : s;
            }
            case ENUM -> {
                String s = String.valueOf(v).toUpperCase(Locale.ROOT);
                if (!c.values().contains(s)) throw bad("\"" + c.key() + "\" must be one of " + c.values() + ".");
                return s;
            }
            default -> {
                if (c.key().equals("stealTakePercent")) {
                    double d;
                    try {
                        d = Double.parseDouble(String.valueOf(v));
                    } catch (NumberFormatException e) {
                        throw bad("stealTakePercent must be a number.");
                    }
                    if (d < 0 || d > 100) throw bad("stealTakePercent must be between 0 and 100.");
                    return java.math.BigDecimal.valueOf(d);
                }
                long n;
                try {
                    n = new java.math.BigDecimal(String.valueOf(v)).longValueExact();
                } catch (NumberFormatException | ArithmeticException e) {
                    throw bad("\"" + c.key() + "\" must be a whole number.");
                }
                if (n < c.min() || n > c.max()) throw bad("\"" + c.key() + "\" must be between " + c.min() + " and " + c.max() + ".");
                return c.type() == T.INT ? (Object) (int) n : (Object) n;
            }
        }
    }

    private Section section(String name) {
        Section s = SECTIONS.get(name);
        if (s == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown section.");
        return s;
    }

    /* ---------------- lists ---------------- */

    @GetMapping("/cfg/{section}")
    public List<Map<String, Object>> list(@PathVariable String section, @RequestParam(required = false) Long parent) {
        Section s = section(section);
        String where = "";
        List<Object> args = new ArrayList<>();
        if (parent != null && s.filterKey() != null) {
            where = " where " + s.cols().stream().filter(c -> c.key().equals(s.filterKey())).findFirst().get().column() + " = ?";
            args.add(parent);
        }
        List<Map<String, Object>> rows = jdbc.queryForList("select * from " + s.table() + where + " order by " + s.order(), args.toArray());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", ((Number) r.get("id")).longValue());
            for (Col c : s.cols()) m.put(c.key(), r.get(c.column()));
            out.add(m);
        }
        return out;
    }

    private void checkRef(String kind, long ref) {
        String table = switch (kind) {
            case "CHEST" -> "parivar_chest_types";
            case "CARD", "GACHA_CARD" -> "parivar_gacha_machines";
            case "BARRIER" -> "parivar_barrier_types";
            default -> null;
        };
        if (table == null) return;
        Integer n = jdbc.queryForObject("select count(*) from " + table + " where id = ?", Integer.class, ref);
        if (n == null || n == 0) throw bad("That " + kind.toLowerCase(Locale.ROOT) + " (ID " + ref + ") doesn't exist.");
    }

    /** Add or change rows: a row with an id is changed, a row without one is added. Everything is checked before anything is saved. */
    @PutMapping("/cfg/{section}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public List<Map<String, Object>> save(@PathVariable String section, @RequestBody List<Map<String, Object>> rows, Authentication auth) {
        Section s = section(section);
        if (rows == null || rows.isEmpty() || rows.size() > 500) throw bad("Send 1 to 500 rows.");
        List<Map<String, Object>> clean = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (Col c : s.cols()) m.put(c.column(), coerce(c, r.get(c.key())));
            if (m.containsKey("kind") && m.containsKey("ref")) checkRef((String) m.get("kind"), ((Number) m.get("ref")).longValue());
            if (section.equals("rank-rewards") && ((Number) m.get("rank_to")).intValue() < ((Number) m.get("rank_from")).intValue()) throw bad("\"rankTo\" can't be below \"rankFrom\".");
            if (section.equals("treasure-rewards")) {
                String k = (String) m.get("kind");
                if (k.equals("GIFT") && (m.get("gift_id") == null || jdbc.queryForObject("select count(*) from gifts where id = ?", Integer.class, m.get("gift_id")) == 0)) throw bad("Gift reward: write the ID of an existing gift.");
                if (k.equals("ITEM") && jdbc.queryForObject("select count(*) from reward_rule_items where id = ?", Integer.class, m.get("ref")) == 0) throw bad("Item reward: \"ref\" must be the ID of an item made in Spend rewards.");
                if (k.equals("COLLECTIBLE") && jdbc.queryForObject("select count(*) from parivar_collectibles where id = ?", Integer.class, m.get("ref")) == 0) throw bad("Library reward: \"ref\" must be the ID of an item in the Parivar library.");
                if (k.equals("PROFILE_TITLE") && jdbc.queryForObject("select count(*) from profile_titles where id = ?", Integer.class, m.get("ref")) == 0) throw bad("Profile title reward: \"ref\" must be the ID of a title made in the Profile titles page.");
                if (k.equals("VIP_CARD") && ((Number) m.get("amount")).longValue() < 1) throw bad("VIP card: amount is the number of days (at least 1).");
            }
            if (section.equals("treasure-tiers") && !String.valueOf(m.get("code")).matches("[A-Z_]{2,12}")) throw bad("Tier code is capital letters only (like NORMAL).");
            m.put("__id", r.get("id") == null ? null : Long.valueOf(String.valueOf(r.get("id"))));
            clean.add(m);
        }
        for (Map<String, Object> m : clean) {
            Long id = (Long) m.remove("__id");
            List<String> cols = new ArrayList<>(m.keySet());
            Object[] vals = cols.stream().map(m::get).toArray();
            if (id == null) {
                jdbc.update("insert into " + s.table() + " (" + String.join(", ", cols) + ") values (" + String.join(", ", Collections.nCopies(cols.size(), "?")) + ")", vals);
            } else {
                Object[] all = Arrays.copyOf(vals, vals.length + 1);
                all[vals.length] = id;
                int n = jdbc.update("update " + s.table() + " set " + String.join(" = ?, ", cols) + " = ? where id = ?", all);
                if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Row " + id + " doesn't exist.");
            }
        }
        audit(auth, "CONFIG " + section, clean.size() + " row(s) saved");
        return list(section, null);
    }

    @DeleteMapping("/cfg/{section}/{id:[0-9]+}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Map<String, Object> delete(@PathVariable String section, @PathVariable Long id, Authentication auth) {
        Section s = section(section);
        if (!s.deletable()) throw new ResponseStatusException(HttpStatus.CONFLICT, "This can't be deleted because Parivars may own or have used it. Turn it off instead.");
        if (jdbc.update("delete from " + s.table() + " where id = ?", id) == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That row doesn't exist.");
        audit(auth, "CONFIG " + section, "row " + id + " deleted");
        return Map.of("deleted", id);
    }

    /* ---------------- extra settings ---------------- */

    @GetMapping("/settings/extra")
    public Map<String, Object> extra() {
        Map<String, Object> row = jdbc.queryForMap("select * from parivar_settings where id = 1");
        Map<String, Object> m = new LinkedHashMap<>();
        for (Col c : EXTRA.values()) m.put(c.key(), row.get(c.column()));
        return m;
    }

    @PutMapping("/settings/extra")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Map<String, Object> saveExtra(@RequestBody Map<String, Object> body, Authentication auth) {
        if (body == null || body.isEmpty()) throw bad("Nothing to save.");
        List<String> sets = new ArrayList<>();
        List<Object> vals = new ArrayList<>();
        for (Map.Entry<String, Object> e : body.entrySet()) {
            Col c = EXTRA.get(e.getKey());
            if (c == null) throw bad("Unknown setting \"" + e.getKey() + "\".");
            sets.add(c.column() + " = ?");
            vals.add(coerce(c, e.getValue()));
        }
        jdbc.update("update parivar_settings set " + String.join(", ", sets) + ", updated_at = now(), updated_by = ? where id = 1", appendName(vals, auth));
        audit(auth, "SETTINGS+", body.toString());
        return extra();
    }

    private Object[] appendName(List<Object> vals, Authentication auth) {
        List<Object> all = new ArrayList<>(vals);
        all.add(auth == null ? null : auth.getName());
        return all.toArray();
    }

    /* ---------------- read-only logs and numbers ---------------- */

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("parivars", jdbc.queryForObject("select count(*) from parivars where status = 'ACTIVE'", Long.class));
        m.put("members", jdbc.queryForObject("select count(*) from parivar_members m join parivars p on p.id = m.parivar_id where p.status = 'ACTIVE'", Long.class));
        m.put("totalFunds", jdbc.queryForObject("select coalesce(sum(funds), 0) from parivars where status = 'ACTIVE'", Long.class));
        m.put("choriToday", jdbc.queryForObject("select count(*) from parivar_steal_log where created_at >= date_trunc('day', now())", Long.class));
        m.put("gachaToday", jdbc.queryForObject("select count(*) from parivar_gacha_log where created_at >= date_trunc('day', now())", Long.class));
        m.put("chestsOpen", jdbc.queryForObject("select count(*) from parivar_chests where status = 'OPEN'", Long.class));
        m.put("kavachOn", jdbc.queryForObject("select count(distinct parivar_id) from parivar_barriers where expires_at > now()", Long.class));
        m.put("donatedToday", jdbc.queryForObject("select coalesce(sum(delta), 0) from parivar_funds_ledger where kind = 'DONATION' and created_at >= date_trunc('day', now())", Long.class));
        return m;
    }

    @GetMapping("/logs/{kind}")
    public List<Map<String, Object>> logs(@PathVariable String kind) {
        return switch (kind) {
            case "steal" -> jdbc.queryForList("select l.id, l.result, l.amount, l.barrier_strength as \"barrierStrength\", l.created_at as \"createdAt\", tp.name as thief, vp.name as target, u.full_name as \"user\" from parivar_steal_log l"
                    + " join parivars tp on tp.id = l.thief_parivar join parivars vp on vp.id = l.target_parivar left join users u on u.id = l.thief_user order by l.id desc limit 100");
            case "gacha" -> jdbc.queryForList("select g.id, p.name as parivar, u.full_name as \"user\", m.name as machine, g.label, g.rarity, g.paid_with as \"paidWith\", g.created_at as \"createdAt\" from parivar_gacha_log g"
                    + " join parivars p on p.id = g.parivar_id left join users u on u.id = g.user_id left join parivar_gacha_machines m on m.id = g.machine_id order by g.id desc limit 100");
            case "funds" -> jdbc.queryForList("select f.id, p.name as parivar, u.full_name as \"user\", f.delta, f.balance_after as balance, f.kind, f.created_at as \"createdAt\" from parivar_funds_ledger f"
                    + " join parivars p on p.id = f.parivar_id left join users u on u.id = f.user_id order by f.id desc limit 100");
            case "chests" -> jdbc.queryForList("select c.id, p.name as parivar, c.name, c.pieces, c.claimed, c.status, c.created_at as \"createdAt\" from parivar_chests c join parivars p on p.id = c.parivar_id order by c.id desc limit 100");
            case "treasure" -> jdbc.queryForList("select d.id, p.name as parivar, d.chest_name as chest, d.tier_code as tier, d.mode, s.full_name as \"sentBy\", d.member_count as members, d.total_coins as coins, d.total_diamonds as diamonds, t.full_name as \"top\", d.created_at as \"createdAt\""
                    + " from treasure_distributions d join parivars p on p.id = d.parivar_id left join users s on s.id = d.sent_by left join users t on t.id = d.top_user order by d.id desc limit 100");
            case "treasure-members" -> jdbc.queryForList("select x.id, u.full_name as member, p.name as parivar, d.chest_name as chest, d.tier_code as tier, x.coins, x.diamonds, x.items, x.created_at as \"createdAt\""
                    + " from treasure_payouts x join users u on u.id = x.user_id join parivars p on p.id = x.parivar_id join treasure_distributions d on d.id = x.distribution_id order by x.id desc limit 200");
            case "treasure-chests" -> jdbc.queryForList("select c.id, p.name as parivar, c.name, c.status, c.pool_coins as coins, c.pool_diamonds as diamonds, u.full_name as \"claimedBy\", c.unlock_at as \"unlockAt\", c.earned_at as \"createdAt\""
                    + " from treasure_chests c join parivars p on p.id = c.parivar_id left join users u on u.id = c.claimed_by order by c.id desc limit 100");
            case "treasure-audit" -> jdbc.queryForList("select a.id, a.chest_id as chest, a.action, a.detail, u.full_name as \"user\", a.created_at as \"createdAt\" from treasure_audit a left join users u on u.id = a.user_id order by a.id desc limit 200");
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown log.");
        };
    }
}
