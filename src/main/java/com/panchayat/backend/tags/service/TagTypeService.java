package com.panchayat.backend.tags.service;

import com.panchayat.backend.admin.media.AdminMediaService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** The tag types shown on mic seats (Besties, Bro and sis ...): the admin edits the names, colours, picture and animation, and adds new ones. */
@Service
public class TagTypeService {

    private static final Pattern ID = Pattern.compile("^[a-z0-9]{2,20}$");
    private static final Pattern COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");

    private final JdbcTemplate jdbc;
    private final AdminMediaService media;

    private volatile Set<String> known;
    private volatile long knownAt;

    public TagTypeService(JdbcTemplate jdbc, AdminMediaService media) {
        this.jdbc = jdbc;
        this.media = media;
    }

    private static Map<String, Object> row(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getString("id"));
        m.put("label", rs.getString("label"));
        m.put("one", rs.getString("one_label"));
        m.put("emoji", rs.getString("emoji"));
        m.put("tint", rs.getString("tint"));
        m.put("deep", rs.getString("deep"));
        m.put("imageUrl", rs.getString("image_url"));
        m.put("animationUrl", rs.getString("animation_url"));
        m.put("sortOrder", rs.getInt("sort_order"));
        m.put("active", rs.getBoolean("active"));
        return m;
    }

    /** What the app shows: every type that is switched on (people with an old tag of a switched-off type still see it via listAll). */
    public List<Map<String, Object>> list(boolean all) {
        return jdbc.query("select * from tag_types" + (all ? "" : " where active") + " order by sort_order, id", (rs, i) -> row(rs));
    }

    /** The app needs every type to draw old tags, so the public list has the switched-off ones too, flagged. */
    public List<Map<String, Object>> listAll() {
        return list(true);
    }

    /** May a new tag use this type? (Only types that are switched on.) */
    public boolean isChoosable(String id) {
        if (id == null) return false;
        long now = System.currentTimeMillis();
        Set<String> k = known;
        if (k == null || now - knownAt > 30_000) {
            k = Set.copyOf(jdbc.queryForList("select id from tag_types where active", String.class));
            known = k;
            knownAt = now;
        }
        return k.contains(id);
    }

    private void bust() {
        known = null;
    }

    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }

    private static String text(Object v, int max, String what) {
        String s = v == null ? "" : String.valueOf(v).strip();
        if (s.isEmpty()) throw bad(what + " can't be empty.");
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String color(Object v, String what) {
        String s = v == null ? "" : String.valueOf(v).strip();
        if (!COLOR.matcher(s).matches()) throw bad(what + " must be a colour like #ff7fa8.");
        return s;
    }

    public Map<String, Object> create(Map<String, Object> in) {
        String id = String.valueOf(in.getOrDefault("id", "")).strip().toLowerCase();
        if (!ID.matcher(id).matches()) throw bad("The ID must be 2 to 20 small letters or digits, like cousins.");
        Integer exists = jdbc.queryForObject("select count(*) from tag_types where id = ?", Integer.class, id);
        if (exists != null && exists > 0) throw bad("A tag with this ID already exists.");
        jdbc.update("insert into tag_types (id, label, one_label, emoji, tint, deep, sort_order, active) values (?, ?, ?, ?, ?, ?, ?, true)",
                id, text(in.get("label"), 40, "The name"), text(in.get("one") == null ? in.get("label") : in.get("one"), 30, "The short name"),
                in.get("emoji") == null || String.valueOf(in.get("emoji")).isBlank() ? "🏷️" : String.valueOf(in.get("emoji")).strip(),
                color(in.getOrDefault("tint", "#ffb3cf"), "Light colour"), color(in.getOrDefault("deep", "#b0245a"), "Deep colour"),
                in.get("sortOrder") instanceof Number n ? n.intValue() : 99);
        bust();
        return get(id);
    }

    public Map<String, Object> get(String id) {
        List<Map<String, Object>> l = jdbc.query("select * from tag_types where id = ?", (rs, i) -> row(rs), id);
        if (l.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tag not found");
        return l.get(0);
    }

    public Map<String, Object> update(String id, Map<String, Object> in) {
        get(id);
        if (in.containsKey("label")) jdbc.update("update tag_types set label = ? where id = ?", text(in.get("label"), 40, "The name"), id);
        if (in.containsKey("one")) jdbc.update("update tag_types set one_label = ? where id = ?", text(in.get("one"), 30, "The short name"), id);
        if (in.containsKey("emoji")) jdbc.update("update tag_types set emoji = ? where id = ?", text(in.get("emoji"), 16, "The emoji"), id);
        if (in.containsKey("tint")) jdbc.update("update tag_types set tint = ? where id = ?", color(in.get("tint"), "Light colour"), id);
        if (in.containsKey("deep")) jdbc.update("update tag_types set deep = ? where id = ?", color(in.get("deep"), "Deep colour"), id);
        if (in.get("sortOrder") instanceof Number n) jdbc.update("update tag_types set sort_order = ? where id = ?", n.intValue(), id);
        if (in.get("active") instanceof Boolean b) jdbc.update("update tag_types set active = ? where id = ?", b, id);
        bust();
        return get(id);
    }

    /** Removes the picture or the animation (kind: image or animation). */
    public Map<String, Object> clear(String id, String kind) {
        get(id);
        jdbc.update("update tag_types set " + column(kind) + " = null where id = ?", id);
        return get(id);
    }

    private static String column(String kind) {
        return switch (kind) {
            case "image" -> "image_url";
            case "animation" -> "animation_url";
            default -> throw bad("kind must be image or animation");
        };
    }

    public Map<String, Object> upload(String id, String kind, MultipartFile file) {
        get(id);
        String col = column(kind);
        Set<String> allowed = "image".equals(kind) ? Set.of("PNG", "JPG", "JPEG", "GIF", "WEBP") : Set.of("SVGA", "MP4");
        AdminMediaService.Uploaded u = media.uploadPublic(file, "tags", allowed);
        jdbc.update("update tag_types set " + col + " = ? where id = ?", u.url(), id);
        return get(id);
    }

    /** A type nobody holds can be deleted; one in use is only switched off. */
    public Map<String, Object> delete(String id) {
        get(id);
        Integer used = jdbc.queryForObject("select count(*) from user_tags where relation = ?", Integer.class, id);
        if (used != null && used > 0) {
            jdbc.update("update tag_types set active = false where id = ?", id);
            bust();
            return Map.of("deleted", false, "switchedOff", true, "inUse", used);
        }
        jdbc.update("delete from tag_types where id = ?", id);
        bust();
        return Map.of("deleted", true);
    }
}
