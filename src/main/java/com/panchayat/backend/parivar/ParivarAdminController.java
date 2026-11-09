package com.panchayat.backend.parivar;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.*;

/**
 * Parivar management for staff: every price, limit, level and permission lives in the database and is edited here.
 * Admins can look; only Super Admins change the settings. Every change is written to parivar_config_audit.
 */
@RestController
@RequestMapping("/api/v1/admin/parivar")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
public class ParivarAdminController {

    private final JdbcTemplate jdbc;
    private final ParivarService parivar;

    public ParivarAdminController(JdbcTemplate jdbc, ParivarService parivar) {
        this.jdbc = jdbc;
        this.parivar = parivar;
    }

    public record SettingsRequest(Boolean enabled, Boolean creationOpen, String creationMode, BigDecimal creationInr, BigDecimal coinsPerInr,
                                  Boolean useCoinPackRate, Long manualCoins, Long requiredCharm, Boolean consumeCharm, Boolean onePerUser,
                                  Integer giftCoinsPerExp, Boolean giftCountsForRanking, Long giftUserDailyCap, Integer donationCoinsPerExp, Long minDonation) {
    }

    static final java.util.Set<String> FRAMES = java.util.Set.of("wood", "bronze", "silver", "gold", "ruby", "diamond", "royal");

    public record LevelRow(Integer level, Long expNeeded, Integer capacity, Integer deputyCap, Integer adminCap, String frame) {
    }

    public record PermRow(String role, String perm, Boolean allowed) {
    }

    @ExceptionHandler(ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<Map<String, String>> handle(ResponseStatusException e) {
        return org.springframework.http.ResponseEntity.status(e.getStatusCode()).body(Map.of("message", e.getReason() == null ? "That didn't work" : e.getReason()));
    }

    @ExceptionHandler({org.springframework.dao.PessimisticLockingFailureException.class, org.springframework.dao.DataIntegrityViolationException.class})
    public org.springframework.http.ResponseEntity<Map<String, String>> clash(RuntimeException e) {
        return org.springframework.http.ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "That changed at the same time. Try again."));
    }

    private void audit(Authentication auth, String what, String detail) {
        jdbc.update("insert into parivar_config_audit (admin_name, what, detail) values (?, ?, ?)", auth == null ? null : auth.getName(), what, detail);
    }

    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }

    /** All settings, and what the creation price comes to in coins right now. */
    @GetMapping("/settings")
    public Map<String, Object> settings() {
        Map<String, Object> s = parivar.settings();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", s.get("enabled"));
        m.put("creationOpen", s.get("creation_open"));
        m.put("creationMode", s.get("creation_mode"));
        m.put("creationInr", s.get("creation_inr"));
        m.put("coinsPerInr", s.get("coins_per_inr"));
        m.put("coinsPerInrEffective", parivar.coinsPerInr(s));
        m.put("manualCoins", s.get("manual_coins"));
        m.put("requiredCoins", parivar.requiredCoins(s));
        m.put("requiredCharm", s.get("required_charm"));
        m.put("consumeCharm", s.get("consume_charm"));
        m.put("onePerUser", s.get("one_per_user"));
        m.put("giftCoinsPerExp", s.get("gift_coins_per_exp"));
        m.put("giftCountsForRanking", s.get("gift_counts_for_ranking"));
        m.put("giftUserDailyCap", s.get("gift_user_daily_cap"));
        m.put("donationCoinsPerExp", s.get("donation_coins_per_exp"));
        m.put("minDonation", s.get("min_donation"));
        m.put("updatedBy", s.get("updated_by"));
        return m;
    }

    @PutMapping("/settings")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Map<String, Object> saveSettings(@RequestBody SettingsRequest b, Authentication auth) {
        Map<String, Object> cur = parivar.settings();
        String mode = b.creationMode() == null ? (String) cur.get("creation_mode") : b.creationMode().toUpperCase(Locale.ROOT);
        if (!mode.equals("AUTO") && !mode.equals("MANUAL")) throw bad("The creation price mode must be AUTO or MANUAL.");
        BigDecimal inr = b.creationInr() == null ? (BigDecimal) cur.get("creation_inr") : b.creationInr();
        if (inr.signum() < 0) throw bad("The reference price can't be negative.");
        // useCoinPackRate=true clears the typed rate so it follows the coin packs again
        BigDecimal rate = Boolean.TRUE.equals(b.useCoinPackRate()) ? null : (b.coinsPerInr() != null ? b.coinsPerInr() : (BigDecimal) cur.get("coins_per_inr"));
        if (rate != null && rate.signum() <= 0) throw bad("Coins per rupee must be more than 0.");
        long manual = b.manualCoins() == null ? ((Number) cur.get("manual_coins")).longValue() : b.manualCoins();
        long charm = b.requiredCharm() == null ? ((Number) cur.get("required_charm")).longValue() : b.requiredCharm();
        if (manual < 0 || charm < 0) throw bad("Coins and Charm can't be negative.");
        int giftPer = b.giftCoinsPerExp() == null ? ((Number) cur.get("gift_coins_per_exp")).intValue() : b.giftCoinsPerExp();
        long giftCap = b.giftUserDailyCap() == null ? ((Number) cur.get("gift_user_daily_cap")).longValue() : b.giftUserDailyCap();
        int donPer = b.donationCoinsPerExp() == null ? ((Number) cur.get("donation_coins_per_exp")).intValue() : b.donationCoinsPerExp();
        long minDon = b.minDonation() == null ? ((Number) cur.get("min_donation")).longValue() : b.minDonation();
        if (giftPer < 1 || giftCap < 0 || donPer < 0 || minDon < 1) throw bad("Check the activity numbers: coins per EXP must be 1 or more, the cap 0 or more, the smallest donation 1 or more.");
        boolean enabled = b.enabled() == null ? Boolean.TRUE.equals(cur.get("enabled")) : b.enabled();
        boolean open = b.creationOpen() == null ? Boolean.TRUE.equals(cur.get("creation_open")) : b.creationOpen();
        boolean consume = b.consumeCharm() == null ? Boolean.TRUE.equals(cur.get("consume_charm")) : b.consumeCharm();
        boolean one = b.onePerUser() == null ? Boolean.TRUE.equals(cur.get("one_per_user")) : b.onePerUser();
        boolean giftRank = b.giftCountsForRanking() == null ? Boolean.TRUE.equals(cur.get("gift_counts_for_ranking")) : b.giftCountsForRanking();
        jdbc.update("update parivar_settings set enabled = ?, creation_open = ?, creation_mode = ?, creation_inr = ?, coins_per_inr = ?, manual_coins = ?, required_charm = ?, consume_charm = ?,"
                        + " one_per_user = ?, gift_coins_per_exp = ?, gift_counts_for_ranking = ?, gift_user_daily_cap = ?, donation_coins_per_exp = ?, min_donation = ?, updated_by = ?, updated_at = now() where id = 1",
                enabled, open, mode, inr, rate, manual, charm, consume, one, giftPer, giftRank, giftCap, donPer, minDon, auth == null ? null : auth.getName());
        audit(auth, "SETTINGS", "enabled=" + enabled + " open=" + open + " mode=" + mode + " inr=" + inr + " rate=" + rate + " manual=" + manual + " charm=" + charm + " consume=" + consume
                + " onePerUser=" + one + " giftPerExp=" + giftPer + " giftRank=" + giftRank + " giftCap=" + giftCap + " donPerExp=" + donPer + " minDonation=" + minDon);
        return settings();
    }

    @GetMapping("/levels")
    public List<Map<String, Object>> levels() {
        return jdbc.queryForList("select level, exp_needed as \"expNeeded\", capacity, deputy_cap as \"deputyCap\", admin_cap as \"adminCap\", frame from parivar_levels order by level");
    }

    /** Replace the whole level table. Level 1 must need 0 EXP, EXP must rise, capacity can't shrink. */
    @PutMapping("/levels")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public List<Map<String, Object>> saveLevels(@RequestBody List<LevelRow> rows, Authentication auth) {
        if (rows == null || rows.isEmpty() || rows.size() > 30) throw bad("Give 1 to 30 levels.");
        long prev = -1;
        int prevCap = 0;
        for (int i = 0; i < rows.size(); i++) {
            LevelRow r = rows.get(i);
            if (r.level() == null || r.level() != i + 1 || r.expNeeded() == null || r.capacity() == null || r.deputyCap() == null || r.adminCap() == null) throw bad("Levels must be 1, 2, 3... with all numbers filled in.");
            if (i == 0 && r.expNeeded() != 0) throw bad("Level 1 must need 0 EXP.");
            if (r.expNeeded() <= prev) throw bad("Each level must need more EXP than the one before.");
            if (r.capacity() < 1 || r.capacity() < prevCap || r.deputyCap() < 0 || r.adminCap() < 0) throw bad("Capacity must be 1 or more and can't go down; roles can't be negative.");
            if (r.frame() == null || !FRAMES.contains(r.frame())) throw bad("Pick a frame for every level.");
            prev = r.expNeeded();
            prevCap = r.capacity();
        }
        jdbc.update("delete from parivar_levels");
        for (LevelRow r : rows) jdbc.update("insert into parivar_levels (level, exp_needed, capacity, deputy_cap, admin_cap, frame) values (?, ?, ?, ?, ?, ?)", r.level(), r.expNeeded(), r.capacity(), r.deputyCap(), r.adminCap(), r.frame());
        // A Parivar that sits above the new top level, or below one it already earned, is re-levelled
        jdbc.update("update parivars p set level = coalesce((select max(l.level) from parivar_levels l where l.exp_needed <= p.exp), 1)");
        audit(auth, "LEVELS", rows.size() + " levels");
        return levels();
    }

    @GetMapping("/permissions")
    public List<Map<String, Object>> permissions() {
        return jdbc.queryForList("select role, perm, allowed from parivar_permissions order by role, perm");
    }

    @PutMapping("/permissions")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public List<Map<String, Object>> savePermissions(@RequestBody List<PermRow> rows, Authentication auth) {
        if (rows == null) throw bad("Nothing to save.");
        for (PermRow r : rows) {
            if (r.role() == null || r.perm() == null || r.allowed() == null || !List.of("DEPUTY", "ADMIN", "MEMBER").contains(r.role()) || !ParivarService.PERMS.contains(r.perm()))
                throw bad("Unknown role or permission.");
            jdbc.update("insert into parivar_permissions (role, perm, allowed) values (?, ?, ?) on conflict (role, perm) do update set allowed = excluded.allowed", r.role(), r.perm(), r.allowed());
        }
        audit(auth, "PERMISSIONS", rows.size() + " changes");
        return permissions();
    }

    @GetMapping("/list")
    public Map<String, Object> list(@RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page) {
        int p = Math.max(0, page);
        List<Object> args = new ArrayList<>();
        String where = "";
        if (q != null && !q.isBlank()) {
            where = " where name ilike ? or sign ilike ?";
            String like = "%" + q.trim() + "%";
            args.add(like);
            args.add(like);
        }
        Long total = jdbc.queryForObject("select count(*) from parivars" + where, Long.class, args.toArray());
        args.add(20);
        args.add((long) p * 20);
        List<Map<String, Object>> rows = jdbc.queryForList("select p.id, 4000000 + p.id as code, p.name, p.sign, p.level, p.exp, p.funds, p.status, p.created_at as \"createdAt\","
                + " (select count(*) from parivar_members m where m.parivar_id = p.id) as members, u.full_name as leader from parivars p join users u on u.id = p.leader_id"
                + where + " order by p.id desc limit ? offset ?", args.toArray());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", rows);
        out.put("totalElements", total == null ? 0 : total);
        out.put("page", p);
        return out;
    }

    /** Close a Parivar (members are freed to join another). Super Admin only. */
    @PostMapping("/{id:[0-9]+}/disband")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Map<String, Object> disband(@PathVariable Long id, Authentication auth) {
        int n = jdbc.update("update parivars set status = 'DISBANDED' where id = ? and status = 'ACTIVE'", id);
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That Parivar doesn't exist or is already closed.");
        jdbc.update("delete from parivar_members where parivar_id = ?", id);
        audit(auth, "DISBAND", "parivar " + id);
        return Map.of("disbanded", true);
    }

    @GetMapping("/audit")
    public List<Map<String, Object>> auditLog() {
        return jdbc.queryForList("select id, admin_name as \"adminName\", what, detail, created_at as \"createdAt\" from parivar_config_audit order by id desc limit 100");
    }
}
