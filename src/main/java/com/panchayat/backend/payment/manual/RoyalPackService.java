package com.panchayat.backend.payment.manual;

import com.panchayat.backend.admin.media.MediaUrls;
import com.panchayat.backend.titles.NameFrameService;
import com.panchayat.backend.titles.ProfileTitleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.*;

/**
 * The Royal Pack: sold only through Munshi ji (manual UPI top-up). One purchase gives the pack's coins, the Royal frame with the
 * name the buyer chose on its plate (up to 14 letters), the Royal Nawab title and Panchayat King (male) or Panchayat Queen (female).
 * Everything (price, coins, the frame, the titles, the days they last) is set in the admin panel.
 */
@Service
public class RoyalPackService {

    private static final Logger log = LoggerFactory.getLogger(RoyalPackService.class);

    private final JdbcTemplate jdbc;
    @Autowired @Lazy private NameFrameService nameFrames;
    @Autowired @Lazy private ProfileTitleService titles;

    public RoyalPackService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }

    Map<String, Object> row() {
        return jdbc.queryForMap("select * from royal_pack_settings where id = 1");
    }

    private Map<String, Object> frame(Object frameId) {
        if (frameId == null) return null;
        List<Map<String, Object>> f = jdbc.queryForList("select * from name_frames where id = ?", ((Number) frameId).longValue());
        if (f.isEmpty()) return null;
        Map<String, Object> r = f.get(0);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(r.get("id")));
        m.put("name", r.get("name"));
        m.put("imageUrl", NameFrameService.publicUrl((String) r.get("image_url")));
        m.put("active", r.get("active"));
        m.put("scale", ((Number) r.get("frame_scale")).doubleValue());
        m.put("y", ((Number) r.get("frame_y")).doubleValue());
        m.put("size", ((Number) r.get("display_size")).doubleValue());
        m.put("plateX", ((Number) r.get("plate_x")).doubleValue());
        m.put("plateY", ((Number) r.get("plate_y")).doubleValue());
        m.put("plateW", ((Number) r.get("plate_w")).doubleValue());
        m.put("plateH", ((Number) r.get("plate_h")).doubleValue());
        m.put("color", r.get("text_color"));
        m.put("stroke", r.get("stroke_color"));
        return m;
    }

    private Map<String, Object> title(Object titleId) {
        if (titleId == null) return null;
        List<Map<String, Object>> t = jdbc.queryForList("select * from profile_titles where id = ?", ((Number) titleId).longValue());
        if (t.isEmpty()) return null;
        Map<String, Object> r = t.get(0);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(r.get("id")));
        m.put("name", r.get("name"));
        m.put("iconUrl", MediaUrls.link((String) r.get("icon_url")));
        m.put("colorFrom", r.get("color_from"));
        m.put("colorTo", r.get("color_to"));
        m.put("textColor", r.get("text_color"));
        m.put("rarity", r.get("rarity"));
        return m;
    }

    /** Everything about the pack (admin panel). */
    public Map<String, Object> settings() {
        Map<String, Object> s = row();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", Boolean.TRUE.equals(s.get("enabled")));
        out.put("name", s.get("name"));
        out.put("priceInr", ((Number) s.get("price_inr")).doubleValue());
        out.put("coins", ((Number) s.get("coins")).longValue());
        out.put("frameId", s.get("frame_id") == null ? null : String.valueOf(s.get("frame_id")));
        out.put("frameDays", ((Number) s.get("frame_days")).intValue());
        out.put("titleDays", ((Number) s.get("title_days")).intValue());
        out.put("nawabTitleId", s.get("nawab_title_id") == null ? null : String.valueOf(s.get("nawab_title_id")));
        out.put("kingTitleId", s.get("king_title_id") == null ? null : String.valueOf(s.get("king_title_id")));
        out.put("queenTitleId", s.get("queen_title_id") == null ? null : String.valueOf(s.get("queen_title_id")));
        out.put("badge", s.get("badge"));
        out.put("frame", frame(s.get("frame_id")));
        out.put("nawab", title(s.get("nawab_title_id")));
        out.put("king", title(s.get("king_title_id")));
        out.put("queen", title(s.get("queen_title_id")));
        out.put("sold", jdbc.queryForObject("select count(*) from manual_topups where kind = 'ROYAL' and status = 'APPROVED'", Long.class));
        return out;
    }

    private static Long idOrNull(Object v) {
        if (v == null || String.valueOf(v).isBlank()) return null;
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw bad("Pick from the list.");
        }
    }

    @Transactional
    public Map<String, Object> save(Map<String, Object> in) {
        if (in.get("enabled") instanceof Boolean b) jdbc.update("update royal_pack_settings set enabled = ? where id = 1", b);
        if (in.containsKey("name")) {
            String n = String.valueOf(in.get("name")).strip();
            if (n.length() < 2 || n.length() > 40) throw bad("The pack name needs 2 to 40 letters.");
            jdbc.update("update royal_pack_settings set name = ? where id = 1", n);
        }
        if (in.containsKey("priceInr")) {
            BigDecimal p;
            try {
                p = new BigDecimal(String.valueOf(in.get("priceInr")).trim());
            } catch (NumberFormatException e) {
                throw bad("The price must be a number.");
            }
            if (p.compareTo(BigDecimal.ONE) < 0 || p.compareTo(new BigDecimal("1000000")) > 0) throw bad("The price must be ₹1 to ₹10,00,000.");
            jdbc.update("update royal_pack_settings set price_inr = ? where id = 1", p);
        }
        if (in.containsKey("coins")) {
            long c;
            try {
                c = Long.parseLong(String.valueOf(in.get("coins")).trim());
            } catch (NumberFormatException e) {
                throw bad("Coins must be a whole number.");
            }
            if (c < 0 || c > 10_000_000_000L) throw bad("Coins must be 0 to 1000 crore.");
            jdbc.update("update royal_pack_settings set coins = ? where id = 1", c);
        }
        for (String[] d : new String[][]{{"frameDays", "frame_days"}, {"titleDays", "title_days"}}) {
            if (!in.containsKey(d[0])) continue;
            int v;
            try {
                v = Integer.parseInt(String.valueOf(in.get(d[0])).trim());
            } catch (NumberFormatException e) {
                throw bad("Days must be a whole number.");
            }
            if (v < 0 || v > 3650) throw bad("Days must be 0 to 3650 (0 = never ends).");
            jdbc.update("update royal_pack_settings set " + d[1] + " = ? where id = 1", v);
        }
        if (in.containsKey("frameId")) {
            Long f = idOrNull(in.get("frameId"));
            if (f != null && jdbc.queryForObject("select count(*) from name_frames where id = ?", Long.class, f) == 0) throw bad("That frame doesn't exist.");
            jdbc.update("update royal_pack_settings set frame_id = ? where id = 1", f);
        }
        for (String[] t : new String[][]{{"nawabTitleId", "nawab_title_id"}, {"kingTitleId", "king_title_id"}, {"queenTitleId", "queen_title_id"}}) {
            if (!in.containsKey(t[0])) continue;
            Long id = idOrNull(in.get(t[0]));
            if (id != null && jdbc.queryForObject("select count(*) from profile_titles where id = ?", Long.class, id) == 0) throw bad("That title doesn't exist.");
            jdbc.update("update royal_pack_settings set " + t[1] + " = ? where id = 1", id);
        }
        if (in.containsKey("badge")) {
            String b = String.valueOf(in.get("badge")).strip();
            jdbc.update("update royal_pack_settings set badge = ? where id = 1", b.length() > 24 ? b.substring(0, 24) : b);
        }
        jdbc.update("update royal_pack_settings set updated_at = now() where id = 1");
        return settings();
    }

    /** KING / QUEEN from the person's gender, or null when it isn't male or female. */
    public String titleFor(Long userId) {
        List<String> g = jdbc.queryForList("select upper(coalesce(gender, '')) from users where id = ?", String.class, userId);
        String s = g.isEmpty() ? "" : g.get(0);
        return "MALE".equals(s) || "M".equals(s) ? "KING" : "FEMALE".equals(s) || "F".equals(s) ? "QUEEN" : null;
    }

    /** What the app shows in the wallet and the Munshi ji sheet. On sale only when the pack and Munshi ji's UPI top-up are both on and a frame is set. */
    public Map<String, Object> publicInfo(Long userId, boolean munshiOn) {
        Map<String, Object> s = settings();
        Map<String, Object> out = new LinkedHashMap<>();
        boolean on = munshiOn && Boolean.TRUE.equals(s.get("enabled")) && s.get("frame") != null;
        out.put("enabled", on);
        if (!on) return out;
        for (String k : List.of("name", "priceInr", "coins", "frameDays", "titleDays", "badge", "frame", "nawab", "king", "queen")) out.put(k, s.get(k));
        out.put("myTitle", userId == null ? null : titleFor(userId));
        return out;
    }

    /** KING / QUEEN for a new request: the gender decides; only someone without male / female set picks. */
    String chooseTitle(Long userId, String asked) {
        String g = titleFor(userId);
        if (g != null) return g;
        String a = asked == null ? "" : asked.trim().toUpperCase(Locale.ROOT);
        if (!a.equals("KING") && !a.equals("QUEEN")) throw bad("Choose Panchayat King or Panchayat Queen.");
        return a;
    }

    /** What was delivered, for Munshi ji's message: one line per thing. */
    public record Delivery(List<String> lines, String frameUrl) {
    }

    /**
     * Gives everything of an approved Royal Pack except the coins (the caller adds those in the same transaction).
     * The frame is put on right away. The titles are put on when there is room.
     */
    @Transactional
    public Delivery deliver(long userId, String plateName, String royalTitle) {
        Map<String, Object> s = row();
        List<String> lines = new ArrayList<>();
        int frameDays = ((Number) s.get("frame_days")).intValue();
        int titleDays = ((Number) s.get("title_days")).intValue();
        String frameUrl = null;
        if (s.get("frame_id") != null) {
            long frameId = ((Number) s.get("frame_id")).longValue();
            Long rid = nameFrames.grant(userId, frameId, frameDays, plateName);
            // Put it on now (it replaces whatever frame they wore)
            jdbc.update("update user_rewards set equipped = false where user_id = ? and slot = 'FRAME' and equipped and id <> ?", userId, rid);
            jdbc.update("update user_rewards set equipped = true where id = ?", rid);
            Map<String, Object> f = frame(frameId);
            frameUrl = f == null ? null : (String) f.get("imageUrl");
            lines.add("👑 " + (f == null ? "Royal frame" : f.get("name")) + " with your name \"" + plateName + "\"" + days(frameDays) + " (already on)");
        }
        List<Object> give = new ArrayList<>();
        give.add(s.get("nawab_title_id"));
        give.add("QUEEN".equals(royalTitle) ? s.get("queen_title_id") : s.get("king_title_id"));
        for (Object t : give) {
            if (t == null) continue;
            long tid = ((Number) t).longValue();
            titles.grant(userId, tid, "ROYAL_PACK", titleDays);
            // Worn right away when there is room (an exception here would undo the whole approval, so check first)
            int max = titles.maxWorn();
            Long worn = jdbc.queryForObject("select count(*) from user_titles u join profile_titles p on p.id = u.title_id where u.user_id = ? and u.worn_pos is not null and p.active", Long.class, userId);
            boolean already = jdbc.queryForObject("select count(*) from user_titles where user_id = ? and title_id = ? and worn_pos is not null", Long.class, userId, tid) > 0;
            boolean active = jdbc.queryForObject("select count(*) from profile_titles where id = ? and active", Long.class, tid) > 0;
            if (active && !already && (max == 0 || worn == null || worn < max)) titles.wear(userId, tid);
            else if (!already) log.debug("Royal title {} not worn for {}: title spots full", tid, userId);
            Map<String, Object> m = title(t);
            lines.add("🏅 Title \"" + (m == null ? "" : m.get("name")) + "\"" + days(titleDays));
        }
        return new Delivery(lines, frameUrl);
    }

    private static String days(int d) {
        return d <= 0 ? " (for ever)" : " for " + d + " days";
    }
}
