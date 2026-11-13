package com.panchayat.backend.festival;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.panchayat.backend.admin.media.MediaUrls;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Festival Manager. Each festival has one draft (what the admin is editing and previewing) and published versions that never change.
 * The app shows the festival whose published version is on and inside its dates. Every config is cleaned here: only known fields,
 * colours as #rrggbb, short texts, files only from our own upload folder, so a draft can never put anything unsafe in front of users.
 */
@Service
public class FestivalService {

    private static final Pattern COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private static final Pattern PATH = Pattern.compile("^/uploads/media/festival/[A-Za-z0-9._-]+$");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> BUTTONS = Set.of("pill", "rounded", "square");
    private static final Set<String> MODES = Set.of("dark", "light");

    private final JdbcTemplate jdbc;

    public FestivalService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }

    /* ======================= config ======================= */

    @SuppressWarnings("unchecked")
    private static Map<String, Object> obj(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
    }

    private static String color(Object v, String dflt, String what) {
        if (v == null || String.valueOf(v).isBlank()) return dflt;
        String s = String.valueOf(v).strip();
        if (!COLOR.matcher(s).matches()) throw bad(what + " must be a colour like #e7bc72.");
        return s.toLowerCase(Locale.ROOT);
    }

    private static String text(Object v, int max, String dflt) {
        if (v == null) return dflt;
        String s = String.valueOf(v).strip();
        if (s.length() > max) throw bad("A text is longer than " + max + " letters.");
        return s;
    }

    private static boolean flag(Object v, boolean dflt) {
        return v == null ? dflt : Boolean.parseBoolean(String.valueOf(v));
    }

    private static String asset(Object v) {
        if (v == null || String.valueOf(v).isBlank()) return null;
        String s = String.valueOf(v).strip();
        // The editor may send back a full link of a file we served: keep only its path
        int i = s.indexOf("/uploads/media/festival/");
        if (i >= 0) {
            s = s.substring(i);
            int q = s.indexOf('?');
            if (q >= 0) s = s.substring(0, q);
        }
        if (!PATH.matcher(s).matches()) throw bad("A picture must be a file uploaded in the Festival Manager.");
        return s;
    }

    private static int intIn(Object v, int lo, int hi, int dflt) {
        if (v == null) return dflt;
        try {
            int n = (int) Math.round(Double.parseDouble(String.valueOf(v)));
            return Math.max(lo, Math.min(hi, n));
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    /** The full, safe config built from whatever the editor sent. Unknown fields are dropped; missing ones get their default. */
    public Map<String, Object> normalize(Object raw) {
        Map<String, Object> in = obj(raw);
        Map<String, Object> theme = obj(in.get("theme")), assets = obj(in.get("assets")), deco = obj(in.get("decorations")), banner = obj(in.get("banner")), popup = obj(in.get("popup")), sec = obj(in.get("sections"));
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("primary", color(theme.get("primary"), "#e7bc72", "The main colour"));
        t.put("accent", color(theme.get("accent"), "#ff687c", "The accent colour"));
        t.put("background", color(theme.get("background"), "#050507", "The background colour"));
        t.put("surface", color(theme.get("surface"), "#15131a", "The card colour"));
        t.put("text", color(theme.get("text"), "#fff1d5", "The text colour"));
        String bs = text(theme.get("buttonStyle"), 10, "pill");
        t.put("buttonStyle", BUTTONS.contains(bs) ? bs : "pill");
        String mode = text(theme.get("mode"), 8, "dark");
        t.put("mode", MODES.contains(mode) ? mode : "dark");
        out.put("theme", t);
        Map<String, Object> a = new LinkedHashMap<>();
        for (String k : List.of("bannerUrl", "logoUrl", "backgroundUrl", "popupImageUrl")) a.put(k, asset(assets.get(k)));
        out.put("assets", a);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("enabled", flag(deco.get("enabled"), false));
        for (String k : List.of("fireworks", "diyas", "lights", "confetti")) d.put(k, flag(deco.get(k), false));
        d.put("intensity", intIn(deco.get("intensity"), 10, 100, 50));
        out.put("decorations", d);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("title", text(banner.get("title"), 60, "Happy Festival"));
        b.put("subtitle", text(banner.get("subtitle"), 120, "Celebrate with your Panchayat"));
        b.put("buttonText", text(banner.get("buttonText"), 24, "Celebrate"));
        out.put("banner", b);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("title", text(popup.get("title"), 60, "Festival offer"));
        p.put("body", text(popup.get("body"), 240, "Send a festival gift to someone you love."));
        p.put("buttonText", text(popup.get("buttonText"), 24, "Open gifts"));
        p.put("showOnce", flag(popup.get("showOnce"), true));
        out.put("popup", p);
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("banner", flag(sec.get("banner"), true));
        s.put("popup", flag(sec.get("popup"), false));
        s.put("decorations", flag(sec.get("decorations"), true));
        s.put("logo", flag(sec.get("logo"), true));
        s.put("background", flag(sec.get("background"), true));
        s.put("giftsHighlight", flag(sec.get("giftsHighlight"), true));
        out.put("sections", s);
        return out;
    }

    private String write(Map<String, Object> cfg) {
        try {
            String s = JSON.writeValueAsString(cfg);
            if (s.length() > 20_000) throw bad("The festival settings are too large.");
            return s;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw bad("The settings could not be saved.");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> read(String json) {
        try {
            return normalize(JSON.readValue(json, Map.class));
        } catch (Exception e) {
            return normalize(Map.of());
        }
    }

    /** The config with picture paths turned into links the app and the admin panel can open. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> withLinks(Map<String, Object> cfg) {
        Map<String, Object> out = new LinkedHashMap<>(cfg);
        Map<String, Object> a = new LinkedHashMap<>((Map<String, Object>) cfg.get("assets"));
        for (Map.Entry<String, Object> e : a.entrySet()) if (e.getValue() != null) e.setValue(MediaUrls.link((String) e.getValue()));
        out.put("assets", a);
        return out;
    }

    /* ======================= admin ======================= */

    private static String iso(Object ts) {
        return ts == null ? null : ((Timestamp) ts).toInstant().toString();
    }

    /** off / draft / scheduled / live / ended */
    private String state(Map<String, Object> f) {
        if (f.get("published_version_id") == null) return "DRAFT";
        if (!Boolean.TRUE.equals(f.get("enabled"))) return "OFF";
        Instant now = Instant.now();
        Timestamp from = (Timestamp) f.get("starts_at"), to = (Timestamp) f.get("ends_at");
        if (from != null && from.toInstant().isAfter(now)) return "SCHEDULED";
        if (to != null && !to.toInstant().isAfter(now)) return "ENDED";
        return "LIVE";
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> f : jdbc.queryForList("select f.*, (select version from festival_versions v where v.id = f.published_version_id) as published_no from festivals f order by f.id desc")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", f.get("id"));
            m.put("name", f.get("name"));
            m.put("state", state(f));
            m.put("startsAt", iso(f.get("starts_at")));
            m.put("endsAt", iso(f.get("ends_at")));
            m.put("publishedVersion", f.get("published_no"));
            m.put("updatedAt", iso(f.get("updated_at")));
            out.add(m);
        }
        return out;
    }

    @Transactional
    public Map<String, Object> create(String name, String admin) {
        String n = name == null ? "" : name.strip();
        if (n.isEmpty() || n.length() > 60) throw bad("Give the festival a name (up to 60 letters).");
        Long id = jdbc.queryForObject("insert into festivals (name, created_by) values (?, ?) returning id", Long.class, n, admin);
        jdbc.update("insert into festival_versions (festival_id, kind, config, created_by) values (?, 'DRAFT', ?, ?)", id, write(normalize(Map.of())), admin);
        audit(id, "CREATE", n, admin);
        return get(id);
    }

    private Map<String, Object> festival(Long id) {
        List<Map<String, Object>> l = jdbc.queryForList("select * from festivals where id = ?", id);
        if (l.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That festival doesn't exist.");
        return l.get(0);
    }

    public Map<String, Object> get(Long id) {
        Map<String, Object> f = festival(id);
        Map<String, Object> draft = jdbc.queryForMap("select * from festival_versions where festival_id = ? and kind = 'DRAFT'", id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("name", f.get("name"));
        out.put("state", state(f));
        out.put("enabled", f.get("enabled"));
        out.put("startsAt", iso(f.get("starts_at")));
        out.put("endsAt", iso(f.get("ends_at")));
        out.put("publishedVersionId", f.get("published_version_id"));
        out.put("draft", withLinks(read((String) draft.get("config"))));
        out.put("draftSavedAt", iso(draft.get("created_at")));
        List<Map<String, Object>> versions = new ArrayList<>();
        for (Map<String, Object> v : jdbc.queryForList("select id, version, note, created_by, published_at from festival_versions where festival_id = ? and kind = 'PUBLISHED' order by version desc", id)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", v.get("id"));
            m.put("version", v.get("version"));
            m.put("note", v.get("note"));
            m.put("by", v.get("created_by"));
            m.put("publishedAt", iso(v.get("published_at")));
            m.put("live", Objects.equals(v.get("id"), f.get("published_version_id")));
            versions.add(m);
        }
        out.put("versions", versions);
        Object pub = f.get("published_version_id");
        out.put("published", pub == null ? null : withLinks(read(jdbc.queryForObject("select config from festival_versions where id = ?", String.class, pub))));
        return out;
    }

    @Transactional
    public Map<String, Object> saveDraft(Long id, String name, Object config, String admin) {
        festival(id);
        if (name != null) {
            String n = name.strip();
            if (n.isEmpty() || n.length() > 60) throw bad("Give the festival a name (up to 60 letters).");
            jdbc.update("update festivals set name = ?, updated_at = now() where id = ?", n, id);
        }
        jdbc.update("update festival_versions set config = ?, created_at = now(), created_by = ? where festival_id = ? and kind = 'DRAFT'", write(normalize(config)), admin, id);
        audit(id, "SAVE_DRAFT", null, admin);
        return get(id);
    }

    public record PublishRequest(String mode, String startsAt, String endsAt, String note) {
    }

    private static Timestamp when(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Timestamp.from(Instant.parse(s));
        } catch (Exception e) {
            throw bad("Use a date and time like 2026-11-01T00:00:00Z.");
        }
    }

    /** Copies the draft into a new published version. NOW starts it at once; SCHEDULE uses the dates. */
    @Transactional
    public Map<String, Object> publish(Long id, PublishRequest req, String admin) {
        Map<String, Object> f = festival(id);
        String mode = req == null || req.mode() == null ? "NOW" : req.mode().toUpperCase(Locale.ROOT);
        Timestamp start, end = req == null ? null : when(req.endsAt());
        if ("SCHEDULE".equals(mode)) {
            start = when(req.startsAt());
            if (start == null) throw bad("Choose when the festival starts.");
            if (start.toInstant().isBefore(Instant.now().minusSeconds(60))) throw bad("The start time is in the past. Use Publish now instead.");
        } else if ("NOW".equals(mode)) {
            start = Timestamp.from(Instant.now());
        } else throw bad("Choose Publish now or Schedule.");
        if (end != null && !end.toInstant().isAfter(start.toInstant())) throw bad("The festival must end after it starts.");
        String cfg = jdbc.queryForObject("select config from festival_versions where festival_id = ? and kind = 'DRAFT'", String.class, id);
        int next = jdbc.queryForObject("select coalesce(max(version), 0) + 1 from festival_versions where festival_id = ? and kind = 'PUBLISHED'", Integer.class, id);
        Long vid = jdbc.queryForObject("insert into festival_versions (festival_id, kind, version, config, note, created_by, published_at) values (?, 'PUBLISHED', ?, ?, ?, ?, now()) returning id",
                Long.class, id, next, cfg, req == null || req.note() == null ? null : (req.note().length() > 200 ? req.note().substring(0, 200) : req.note()), admin);
        jdbc.update("update festivals set published_version_id = ?, enabled = true, starts_at = ?, ends_at = ?, updated_at = now() where id = ?", vid, start, end, id);
        audit(id, "PUBLISH", "v" + next + " " + mode + " from " + start.toInstant() + (end == null ? "" : " to " + end.toInstant()), admin);
        return get(id);
    }

    /** Throws the draft away: it goes back to what is published (or the defaults). */
    @Transactional
    public Map<String, Object> discard(Long id, String admin) {
        Map<String, Object> f = festival(id);
        Object pub = f.get("published_version_id");
        String cfg = pub == null ? write(normalize(Map.of())) : jdbc.queryForObject("select config from festival_versions where id = ?", String.class, pub);
        jdbc.update("update festival_versions set config = ?, created_at = now() where festival_id = ? and kind = 'DRAFT'", cfg, id);
        audit(id, "DISCARD", null, admin);
        return get(id);
    }

    /** Makes an older published version the live one again. */
    @Transactional
    public Map<String, Object> rollback(Long id, Long versionId, String admin) {
        festival(id);
        List<Map<String, Object>> v = jdbc.queryForList("select * from festival_versions where id = ? and festival_id = ? and kind = 'PUBLISHED'", versionId, id);
        if (v.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That version doesn't exist.");
        jdbc.update("update festivals set published_version_id = ?, enabled = true, updated_at = now() where id = ?", versionId, id);
        audit(id, "ROLLBACK", "to v" + v.get(0).get("version"), admin);
        return get(id);
    }

    @Transactional
    public Map<String, Object> setEnabled(Long id, boolean on, String admin) {
        festival(id);
        jdbc.update("update festivals set enabled = ?, updated_at = now() where id = ?", on, id);
        audit(id, on ? "TURN_ON" : "TURN_OFF", null, admin);
        return get(id);
    }

    @Transactional
    public void delete(Long id, String admin) {
        festival(id);
        jdbc.update("delete from festivals where id = ?", id);
        audit(id, "DELETE", null, admin);
    }

    private void audit(Long id, String action, String detail, String admin) {
        jdbc.update("insert into festival_audit (festival_id, action, detail, admin_name) values (?, ?, ?, ?)", id, action, detail, admin);
    }

    public List<Map<String, Object>> auditLog(Long id) {
        return jdbc.queryForList("select action, detail, admin_name as \"by\", created_at as \"at\" from festival_audit where festival_id = ? order by id desc limit 50", id);
    }

    /* ======================= the app ======================= */

    /** The festival the app should show now, or an empty map. If two are on, the one that started last wins. */
    public Map<String, Object> active() {
        List<Map<String, Object>> l = jdbc.queryForList("select f.id, f.name, f.ends_at, v.id as vid, v.version, v.config from festivals f join festival_versions v on v.id = f.published_version_id"
                + " where f.enabled and f.starts_at <= now() and (f.ends_at is null or f.ends_at > now()) order by f.starts_at desc, f.id desc limit 1");
        if (l.isEmpty()) return Map.of("active", false);
        Map<String, Object> f = l.get(0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("active", true);
        out.put("id", f.get("id"));
        out.put("name", f.get("name"));
        out.put("version", f.get("vid"));
        out.put("endsAt", iso(f.get("ends_at")));
        out.put("config", withLinks(read((String) f.get("config"))));
        return out;
    }
}
