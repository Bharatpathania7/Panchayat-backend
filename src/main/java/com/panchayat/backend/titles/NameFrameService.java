package com.panchayat.backend.titles;

import com.panchayat.backend.admin.media.AdminMediaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Name frames: an avatar frame with a name plate that shows the wearer's account name.
 * A person owns one as a wearable item in the Bag (user_rewards, slot FRAME, name_frame_id set), so wearing and taking off work like any frame.
 * They are given by top-up amount (rules) or by the admin to one person.
 */
@Service
public class NameFrameService {

    private static final Logger log = LoggerFactory.getLogger(NameFrameService.class);
    private static final Pattern COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");
    /** Same source as the title rules: gateway top-ups that succeeded plus manual top-ups that were approved. */
    private static final String ALL_TOPUPS = "select user_id, amount::numeric as amount from payment_transactions where purpose = 'COIN_PACK' and status = 'SUCCESS'"
            + " union all select user_id, amount_inr::numeric from manual_topups where status = 'APPROVED'";

    private final JdbcTemplate jdbc;
    private final AdminMediaService media;

    public NameFrameService(JdbcTemplate jdbc, AdminMediaService media) {
        this.jdbc = jdbc;
        this.media = media;
    }

    /** A picture the website itself ships (/frames/...) stays a site path; uploads get their full link. */
    public static String publicUrl(String stored) {
        if (stored == null || stored.isBlank()) return null;
        return stored.startsWith("/frames/") ? stored : com.panchayat.backend.admin.media.MediaUrls.link(stored);
    }

    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }

    /* ---------------- giving ---------------- */

    /** Puts the frame in the person's Bag (or renews it): days = 0 never ends; otherwise it ends that many days from now (never sooner than it already did). */
    @Transactional
    public void grant(Long userId, Long frameId, int days) {
        grant(userId, frameId, days, null);
    }

    /**
     * Like grant, with the name the person chose for the plate (up to 14 letters; null keeps what they had, or their account name).
     * Returns the Bag item's id.
     */
    @Transactional
    public Long grant(Long userId, Long frameId, int days, String plateName) {
        return grant(userId, frameId, days, plateName, null);
    }

    /** Like grant(.., plateName), with the font for the plate (one of PLATE_FONTS; null keeps what they had). */
    @Transactional
    public Long grant(Long userId, Long frameId, int days, String plateName, String plateFont) {
        grantOnly(userId, frameId, days);
        Long rid = jdbc.queryForObject("select id from user_rewards where user_id = ? and name_frame_id = ? order by id limit 1", Long.class, userId, frameId);
        if (plateName != null && !plateName.isBlank()) jdbc.update("update user_rewards set plate_name = ? where id = ?", plateName, rid);
        if (plateFont != null && !plateFont.isBlank()) jdbc.update("update user_rewards set plate_font = ? where id = ?", plateFont, rid);
        return rid;
    }

    /** The fonts a frame's plate can use: the same as the profile's "Name font". */
    public static final Set<String> PLATE_FONTS = Set.of("default", "pacifico", "dancing", "cinzel", "yatra", "righteous");

    /** A font id from PLATE_FONTS; empty = "default". */
    public static String cleanPlateFont(String raw) {
        String f = raw == null || raw.isBlank() ? "default" : raw.strip().toLowerCase(java.util.Locale.ROOT);
        if (!PLATE_FONTS.contains(f)) throw bad("Choose one of the fonts.");
        return f;
    }

    /** A name for a frame's plate: trimmed, 1 to 14 letters (emoji count as one), no control characters. */
    public static String cleanPlateName(String raw) {
        String s = raw == null ? "" : raw.replaceAll("\\p{Cntrl}", "").replaceAll("\\s+", " ").strip();
        if (s.isEmpty()) throw bad("Write the name for your frame.");
        if (s.codePointCount(0, s.length()) > 14) throw bad("The frame name can have up to 14 letters.");
        return s;
    }

    private void grantOnly(Long userId, Long frameId, int days) {
        List<Map<String, Object>> f = jdbc.queryForList("select name, image_url from name_frames where id = ?", frameId);
        if (f.isEmpty()) throw bad("That frame doesn't exist.");
        String name = (String) f.get(0).get("name");
        String url = (String) f.get(0).get("image_url");
        List<Long> have = jdbc.queryForList("select id from user_rewards where user_id = ? and name_frame_id = ? order by id limit 1", Long.class, userId, frameId);
        if (!have.isEmpty()) {
            if (days == 0) {
                jdbc.update("update user_rewards set expires_at = null, item_name = ?, media_url = ? where id = ?", name, url, have.get(0));
            } else {
                // A frame that never ends stays that way; otherwise renew to `days` from now (never sooner than it already ended)
                jdbc.update("update user_rewards set expires_at = case when expires_at is null then null else greatest(expires_at, now() + (? * interval '1 day')) end,"
                        + " item_name = ?, media_url = ? where id = ?", days, name, url, have.get(0));
            }
            return;
        }
        jdbc.update("insert into user_rewards (user_id, rule_id, rule_item_id, rule_kind, rule_name, item_name, item_type, gift_id, quantity, remaining, slot, media_url, media_format, granted_at, expires_at, name_frame_id)"
                        + " values (?, null, null, 'NAMEFRAME', 'Name frame', ?, 'FRAME', null, 1, null, 'FRAME', ?, 'PNG', now(), case when ? > 0 then now() + (? * interval '1 day') else null end, ?)",
                userId, name.length() > 80 ? name.substring(0, 80) : name, url, days, days, frameId);
    }

    /** Takes the frame away from a person. */
    @Transactional
    public void revoke(Long userId, Long frameId) {
        jdbc.update("delete from user_rewards where user_id = ? and name_frame_id = ?", userId, frameId);
    }

    /** After a top-up: gives every frame whose amount rule the person has now reached. Never throws. */
    public void onTopup(Long userId) {
        try {
            List<Map<String, Object>> rules = jdbc.queryForList("select r.* from name_frame_rules r join name_frames f on f.id = r.frame_id where r.active and f.active");
            if (rules.isEmpty()) return;
            List<BigDecimal> amounts = jdbc.queryForList("select amount from (" + ALL_TOPUPS + ") t where user_id = ?", BigDecimal.class, userId);
            double total = amounts.stream().mapToDouble(BigDecimal::doubleValue).sum();
            double one = amounts.stream().mapToDouble(BigDecimal::doubleValue).max().orElse(0);
            for (Map<String, Object> r : rules) {
                long need = ((Number) r.get("value")).longValue();
                boolean ok = "TOPUP_ONE".equals(r.get("kind")) ? one >= need : total >= need;
                if (ok) grant(userId, ((Number) r.get("frame_id")).longValue(), ((Number) r.get("days")).intValue());
            }
        } catch (Exception e) {
            log.warn("Name frame check for {} failed: {}", userId, e.getMessage());
        }
    }

    /* ---------------- the admin's catalog ---------------- */

    public List<Map<String, Object>> list() {
        return jdbc.queryForList("select f.id, f.name, f.image_url as \"imageUrl\", f.frame_scale as \"frameScale\", f.frame_y as \"frameY\", f.plate_x as \"plateX\", f.plate_y as \"plateY\","
                + " f.plate_w as \"plateW\", f.plate_h as \"plateH\", f.text_color as \"textColor\", f.stroke_color as \"strokeColor\", f.display_size as \"displaySize\", f.active, f.sort_order as \"sortOrder\","
                + " (select count(*) from user_rewards u where u.name_frame_id = f.id and (u.expires_at is null or u.expires_at > now())) as holders"
                + " from name_frames f order by f.sort_order, f.id");
    }

    public Map<String, Object> get(Long id) {
        return list().stream().filter(m -> id.equals(((Number) m.get("id")).longValue())).findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Frame not found"));
    }

    private static double dbl(Object v, String what, double min, double max) {
        double d;
        try {
            d = Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            throw bad(what + " must be a number.");
        }
        if (d < min || d > max) throw bad(what + " must be between " + min + " and " + max + ".");
        return d;
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> in) {
        String name = in.get("name") == null ? "" : String.valueOf(in.get("name")).strip();
        if (name.isEmpty()) throw bad("Give the frame a name.");
        Long id = jdbc.queryForObject("insert into name_frames (name, image_url, sort_order) values (?, '', (select coalesce(max(sort_order), 0) + 1 from name_frames)) returning id", Long.class, name.length() > 40 ? name.substring(0, 40) : name);
        return get(id);
    }

    private static final Map<String, String> COLS = Map.of("plateX", "plate_x", "plateY", "plate_y", "plateW", "plate_w", "plateH", "plate_h");

    @Transactional
    public Map<String, Object> update(Long id, Map<String, Object> in) {
        get(id);
        if (in.containsKey("name")) {
            String n = String.valueOf(in.get("name")).strip();
            if (n.isEmpty()) throw bad("The name can't be empty.");
            n = n.length() > 40 ? n.substring(0, 40) : n;
            jdbc.update("update name_frames set name = ? where id = ?", n, id);
            jdbc.update("update user_rewards set item_name = ? where name_frame_id = ?", n, id);
        }
        if (in.containsKey("frameScale")) jdbc.update("update name_frames set frame_scale = ? where id = ?", dbl(in.get("frameScale"), "Frame scale", 1, 3), id);
        if (in.containsKey("displaySize")) jdbc.update("update name_frames set display_size = ? where id = ?", dbl(in.get("displaySize"), "Size on the seat", 1, 3), id);
        if (in.containsKey("frameY"))jdbc.update("update name_frames set frame_y = ? where id = ?", dbl(in.get("frameY"), "Frame centre", 0.2, 0.9), id);
        for (Map.Entry<String, String> c : COLS.entrySet()) {
            if (in.containsKey(c.getKey())) jdbc.update("update name_frames set " + c.getValue() + " = ? where id = ?", dbl(in.get(c.getKey()), c.getKey(), 0, 100), id);
        }
        if (in.containsKey("textColor")) jdbc.update("update name_frames set text_color = ? where id = ?", color(in.get("textColor")), id);
        if (in.containsKey("strokeColor")) jdbc.update("update name_frames set stroke_color = ? where id = ?", color(in.get("strokeColor")), id);
        if (in.get("active") instanceof Boolean b) jdbc.update("update name_frames set active = ? where id = ?", b, id);
        if (in.get("sortOrder") instanceof Number n) jdbc.update("update name_frames set sort_order = ? where id = ?", n.intValue(), id);
        return get(id);
    }

    private static String color(Object v) {
        String c = String.valueOf(v).strip();
        if (!COLOR.matcher(c).matches()) throw bad("Colours look like #ffe9a8.");
        return c;
    }

    @Transactional
    public Map<String, Object> upload(Long id, MultipartFile file) {
        get(id);
        AdminMediaService.Uploaded u = media.uploadPublic(file, "nameframes", Set.of("PNG", "WEBP", "GIF"));
        jdbc.update("update name_frames set image_url = ? where id = ?", u.url(), id);
        jdbc.update("update user_rewards set media_url = ? where name_frame_id = ?", u.url(), id);
        return get(id);
    }

    @Transactional
    public Map<String, Object> delete(Long id) {
        get(id);
        Integer held = jdbc.queryForObject("select count(*) from user_rewards where name_frame_id = ?", Integer.class, id);
        if (held != null && held > 0) {
            jdbc.update("update name_frames set active = false where id = ?", id);
            return Map.of("deleted", false, "switchedOff", true, "holders", held);
        }
        jdbc.update("delete from name_frames where id = ?", id);
        return Map.of("deleted", true);
    }

    /* ---------------- rules and holders ---------------- */

    public List<Map<String, Object>> rules(Long frameId) {
        return jdbc.queryForList("select id, kind, value, days, active from name_frame_rules where frame_id = ? order by id", frameId);
    }

    @Transactional
    public Map<String, Object> addRule(Long frameId, String kind, Long value, Integer days) {
        get(frameId);
        if (!"TOPUP_TOTAL".equals(kind) && !"TOPUP_ONE".equals(kind)) throw bad("Choose a rule type.");
        if (value == null || value < 1) throw bad("Write the amount in rupees (1 or more).");
        int d = days == null ? 0 : days;
        if (d < 0 || d > 3650) throw bad("Days must be 0 to 3650 (0 = never ends).");
        jdbc.update("insert into name_frame_rules (frame_id, kind, value, days) values (?, ?, ?, ?)", frameId, kind, value, d);
        backfill(frameId, kind, value, d);
        return Map.of("added", true);
    }

    @Transactional
    public void deleteRule(Long frameId, Long ruleId) {
        jdbc.update("delete from name_frame_rules where id = ? and frame_id = ?", ruleId, frameId);
    }

    /** Gives the frame to everybody who already reached a new rule's amount. */
    private void backfill(Long frameId, String kind, long need, int days) {
        String having = "TOPUP_ONE".equals(kind) ? "max(amount) >= ?" : "sum(amount) >= ?";
        List<Long> who = jdbc.queryForList("select user_id from (" + ALL_TOPUPS + ") t group by user_id having " + having, Long.class, need);
        for (Long u : who) grant(u, frameId, days);
    }

    /** Gives a frame to one person, by account id or by public (7 digit) ID. days = 0 never ends. */
    @Transactional
    public Map<String, Object> grantTo(Long frameId, Long userId, Long publicId, int days) {
        get(frameId);
        if (days < 0 || days > 3650) throw bad("Days must be 0 to 3650 (0 = never ends).");
        Long uid = userId;
        if (uid == null) {
            List<Long> u = jdbc.queryForList("select id from users where public_id = ?", Long.class, publicId);
            if (u.isEmpty()) throw bad("No user has the ID " + publicId + ".");
            uid = u.get(0);
        } else if (jdbc.queryForObject("select count(*) from users where id = ?", Long.class, uid) == 0) {
            throw bad("That user doesn't exist.");
        }
        grant(uid, frameId, days);
        return Map.of("granted", true);
    }

    public List<Map<String, Object>> holders(Long frameId) {
        return jdbc.queryForList("select u.user_id as \"userId\", p.public_id as \"publicId\", p.full_name as name, u.expires_at as \"expiresAt\", u.equipped from user_rewards u join users p on p.id = u.user_id"
                + " where u.name_frame_id = ? order by u.granted_at desc limit 200", frameId);
    }
}
