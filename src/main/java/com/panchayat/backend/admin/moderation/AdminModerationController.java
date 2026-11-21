package com.panchayat.backend.admin.moderation;

import com.panchayat.backend.admin.media.MediaUrls;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

/**
 * Chat moderation (admin panel):
 *   GET    /api/v1/admin/moderation/words                     the banned-word list
 *   POST   /api/v1/admin/moderation/words        { word }      add one
 *   DELETE /api/v1/admin/moderation/words/{id}                 remove one
 *   GET    /api/v1/admin/moderation/violations?reviewed=&limit=   flagged chat messages, newest first
 *   POST   /api/v1/admin/moderation/violations/{id}/reviewed   mark it looked at
 *   GET    /api/v1/admin/moderation/images?limit=              recent Moments photos/videos, for a manual
 *                                                               look (no automated NSFW scan: that would
 *                                                               need a paid third-party API)
 */
@RestController
@RequestMapping("/api/v1/admin/moderation")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
public class AdminModerationController {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    public AdminModerationController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }

    public record WordBody(String word) {
    }

    @GetMapping("/words")
    public List<Map<String, Object>> words() {
        return jdbc.query("select id, word, created_at from moderation_banned_words order by word", (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("word", rs.getString("word"));
            m.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
            return m;
        });
    }

    @PostMapping("/words")
    public Map<String, Object> addWord(@RequestBody WordBody b) {
        String w = b == null || b.word() == null ? "" : b.word().strip().toLowerCase(Locale.ROOT);
        if (w.isBlank() || w.length() > 100) throw bad("Give a word of 1 to 100 letters.");
        Long id = jdbc.queryForObject("insert into moderation_banned_words (word) values (?) on conflict (word) do update set word = excluded.word returning id", Long.class, w);
        return Map.of("id", id, "word", w);
    }

    @DeleteMapping("/words/{id:[0-9]+}")
    public void deleteWord(@PathVariable Long id) {
        jdbc.update("delete from moderation_banned_words where id = ?", id);
    }

    @GetMapping("/violations")
    public List<Map<String, Object>> violations(@RequestParam(required = false) Boolean reviewed, @RequestParam(defaultValue = "100") int limit) {
        String where = reviewed == null ? "" : " where v.reviewed = ?";
        int lim = Math.max(1, Math.min(limit, 300));
        Object[] args = reviewed == null ? new Object[]{lim} : new Object[]{reviewed, lim};
        return jdbc.query("select v.id, v.user_id, v.source, v.room_id, v.matched_word, v.snippet, v.created_at, v.reviewed, u.full_name, u.public_id"
                        + " from moderation_violations v join users u on u.id = v.user_id" + where + " order by v.id desc limit ?",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("userId", String.valueOf(rs.getLong("user_id")));
                    m.put("userName", rs.getString("full_name"));
                    m.put("userPublicId", rs.getObject("public_id"));
                    m.put("source", rs.getString("source"));
                    long roomId = rs.getLong("room_id");
                    m.put("roomId", rs.wasNull() ? null : roomId);
                    m.put("matchedWord", rs.getString("matched_word"));
                    m.put("snippet", rs.getString("snippet"));
                    m.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
                    m.put("reviewed", rs.getBoolean("reviewed"));
                    return m;
                }, args);
    }

    @PostMapping("/violations/{id:[0-9]+}/reviewed")
    public void markReviewed(@PathVariable Long id) {
        jdbc.update("update moderation_violations set reviewed = true where id = ?", id);
    }

    @GetMapping("/images")
    public List<Map<String, Object>> images(@RequestParam(defaultValue = "60") int limit) {
        int lim = Math.max(1, Math.min(limit, 150));
        List<Map<String, Object>> rows = jdbc.query(
                "select m.id, m.user_id, m.media, m.created_at, u.full_name, u.public_id from moments m join users u on u.id = m.user_id"
                        + " where m.deleted_at is null and m.media != '[]'::jsonb order by m.id desc limit ?",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("momentId", String.valueOf(rs.getLong("id")));
                    m.put("userId", String.valueOf(rs.getLong("user_id")));
                    m.put("userName", rs.getString("full_name"));
                    m.put("userPublicId", rs.getObject("public_id"));
                    m.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
                    List<Map<String, Object>> files = new ArrayList<>();
                    try {
                        List<?> l = json.readValue(rs.getString("media"), List.class);
                        for (Object o : l) {
                            if (!(o instanceof Map<?, ?> x)) continue;
                            Map<String, Object> f = new LinkedHashMap<>();
                            f.put("url", MediaUrls.link(String.valueOf(x.get("url"))));
                            f.put("type", x.get("type"));
                            files.add(f);
                        }
                    } catch (Exception ignored) {
                        // nothing to show for a broken media list
                    }
                    m.put("media", files);
                    return m;
                }, lim);
        rows.removeIf(r -> ((List<?>) r.get("media")).isEmpty());
        return rows;
    }
}
