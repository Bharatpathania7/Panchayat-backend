package com.panchayat.backend.events;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Special events on the Discover screen.
 *   GET    /api/v1/events                          what the app shows (active, not ended, in order)
 *   GET    /api/v1/admin/manage/events             all of them
 *   POST   /api/v1/admin/manage/events             add one
 *   PUT    /api/v1/admin/manage/events/{id}        change one
 *   DELETE /api/v1/admin/manage/events/{id}        remove one
 */
@RestController
public class SpecialEventsController {

    static final Set<String> THEMES = Set.of("dusk", "rose", "gold", "night", "monsoon", "ember");
    static final Set<String> MOTIFS = Set.of("banyan", "temple", "huts", "river", "kites", "lanterns");
    private static final String COLS = "id, title, when_text as \"whenText\", body, theme, motif, image_url as \"imageUrl\", active, sort_order as \"sortOrder\", starts_at as \"startsAt\", ends_at as \"endsAt\"";

    private final JdbcTemplate jdbc;

    public SpecialEventsController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record EventBody(String title, String whenText, String body, String theme, String motif, String imageUrl, Boolean active, Integer sortOrder, String startsAt, String endsAt) {
    }

    @GetMapping("/api/v1/events")
    public List<Map<String, Object>> shown() {
        return jdbc.queryForList("select " + COLS + " from special_events where active and (starts_at is null or starts_at <= now()) and (ends_at is null or ends_at > now()) order by sort_order, id");
    }

    @GetMapping("/api/v1/admin/manage/events")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public List<Map<String, Object>> all() {
        return jdbc.queryForList("select " + COLS + " from special_events order by sort_order, id");
    }

    @PostMapping("/api/v1/admin/manage/events")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public Map<String, Object> create(@RequestBody EventBody b) {
        check(b);
        Long id = jdbc.queryForObject("insert into special_events (title, when_text, body, theme, motif, image_url, active, sort_order, starts_at, ends_at) values (?,?,?,?,?,?,?,?,?,?) returning id", Long.class,
                b.title().strip(), nz(b.whenText()), nz(b.body()), b.theme(), b.motif(), blank(b.imageUrl()), b.active() == null || b.active(), b.sortOrder() == null ? 0 : b.sortOrder(), ts(b.startsAt()), ts(b.endsAt()));
        return one(id);
    }

    @PutMapping("/api/v1/admin/manage/events/{id:[0-9]+}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody EventBody b) {
        check(b);
        int n = jdbc.update("update special_events set title=?, when_text=?, body=?, theme=?, motif=?, image_url=?, active=?, sort_order=?, starts_at=?, ends_at=? where id=?",
                b.title().strip(), nz(b.whenText()), nz(b.body()), b.theme(), b.motif(), blank(b.imageUrl()), b.active() == null || b.active(), b.sortOrder() == null ? 0 : b.sortOrder(), ts(b.startsAt()), ts(b.endsAt()), id);
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such event");
        return one(id);
    }

    @DeleteMapping("/api/v1/admin/manage/events/{id:[0-9]+}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public Map<String, Object> delete(@PathVariable Long id) {
        jdbc.update("delete from special_events where id = ?", id);
        return Map.of("ok", true);
    }

    private Map<String, Object> one(Long id) {
        return jdbc.queryForMap("select " + COLS + " from special_events where id = ?", id);
    }

    private static void check(EventBody b) {
        if (b == null || b.title() == null || b.title().isBlank() || b.title().length() > 80) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give the event a title (up to 80 characters).");
        if (b.whenText() != null && b.whenText().length() > 80) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The 'when' line is too long.");
        if (b.body() != null && b.body().length() > 240) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The description is too long (240 characters).");
        if (!THEMES.contains(b.theme()) || !MOTIFS.contains(b.motif())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pick a theme and a picture style from the lists.");
    }

    private static String nz(String s) {
        return s == null ? "" : s.strip();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static Timestamp ts(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Timestamp.from(Instant.parse(s.strip()));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dates must look like 2026-11-20T18:00:00Z.");
        }
    }
}
