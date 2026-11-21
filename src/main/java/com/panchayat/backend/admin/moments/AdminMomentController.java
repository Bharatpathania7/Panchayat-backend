package com.panchayat.backend.admin.moments;

import com.panchayat.backend.admin.media.AdminMediaService;
import com.panchayat.backend.admin.media.MediaUrls;
import com.panchayat.backend.common.PublicIds;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

/**
 * Moments moderation (admin panel):
 *   GET    /api/v1/admin/moments?q=&userId=&includeHidden=&before=&limit=   the feed, newest first
 *   DELETE /api/v1/admin/moments/{id}            hide it (same as the author deleting it): stays in the
 *                                                 database but leaves every feed
 *   POST   /api/v1/admin/moments/{id}/restore    puts a hidden Moment back
 *   DELETE /api/v1/admin/moments/{id}/permanent  erases it for good: the row, its likes, comments and
 *                                                 uploaded files are all gone
 */
@RestController
@RequestMapping("/api/v1/admin/moments")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
public class AdminMomentController {

    private final JdbcTemplate jdbc;
    private final AdminMediaService media;
    private final ObjectMapper json = new ObjectMapper();

    public AdminMomentController(JdbcTemplate jdbc, AdminMediaService media) {
        this.jdbc = jdbc;
        this.media = media;
    }

    private static final String SELECT = "select m.*, u.full_name from moments m join users u on u.id = m.user_id";

    private Map<String, Object> row(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        long id = rs.getLong("id");
        long uid = rs.getLong("user_id");
        m.put("id", String.valueOf(id));
        m.put("userId", String.valueOf(uid));
        m.put("authorName", rs.getString("full_name"));
        m.put("authorPublicId", PublicIds.user(uid));
        m.put("body", rs.getString("body"));
        m.put("visibility", rs.getString("visibility"));
        m.put("likeCount", rs.getInt("like_count"));
        m.put("commentCount", rs.getInt("comment_count"));
        m.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
        java.sql.Timestamp deletedAt = rs.getTimestamp("deleted_at");
        m.put("hidden", deletedAt != null);
        m.put("deletedAt", deletedAt == null ? null : deletedAt.toInstant().toString());
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
            // a broken media list shows the text only
        }
        m.put("media", files);
        return m;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String q, @RequestParam(required = false) Long userId,
                                     @RequestParam(defaultValue = "false") boolean includeHidden,
                                     @RequestParam(required = false) Long before, @RequestParam(defaultValue = "30") int limit) {
        String where = includeHidden ? " where true" : " where m.deleted_at is null";
        List<Object> args = new ArrayList<>();
        if (userId != null) {
            where += " and m.user_id = ?";
            args.add(userId);
        }
        if (q != null && !q.isBlank()) {
            String like = "%" + q.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            where += " and (m.body ilike ? or u.full_name ilike ? or cast(m.id as text) = ?)";
            args.add(like);
            args.add(like);
            args.add(q.trim());
        }
        if (before != null) {
            where += " and m.id < ?";
            args.add(before);
        }
        int lim = Math.max(1, Math.min(limit, 100));
        args.add(lim + 1);
        List<Map<String, Object>> items = jdbc.query(SELECT + where + " order by m.id desc limit ?", (rs, i) -> row(rs), args.toArray());
        boolean more = items.size() > lim;
        List<Map<String, Object>> page = items.subList(0, Math.min(lim, items.size()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", page);
        out.put("next", more && !page.isEmpty() ? page.get(page.size() - 1).get("id") : null);
        return out;
    }

    /** Hide it: same as the author deleting it. It stays in the database (for a repeat-offender history) but leaves every feed. */
    @DeleteMapping("/{id:[0-9]+}")
    public Map<String, Object> hide(@PathVariable Long id) {
        Integer exists = jdbc.queryForObject("select count(*) from moments where id = ?", Integer.class, id);
        if (exists == null || exists == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That Moment doesn't exist.");
        jdbc.update("update moments set deleted_at = now() where id = ? and deleted_at is null", id);
        return Map.of("id", id, "hidden", true);
    }

    /** Puts a hidden Moment back, same as if nothing happened. */
    @PostMapping("/{id:[0-9]+}/restore")
    public Map<String, Object> restore(@PathVariable Long id) {
        if (jdbc.update("update moments set deleted_at = null where id = ?", id) == 0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That Moment doesn't exist.");
        return Map.of("id", id, "hidden", false);
    }

    /** Erases it for good: the row (and its likes/comments, by the foreign keys) and its uploaded files. */
    @DeleteMapping("/{id:[0-9]+}/permanent")
    public Map<String, Object> delete(@PathVariable Long id) {
        List<String> mediaJson = jdbc.queryForList("select media from moments where id = ?", String.class, id);
        if (mediaJson.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That Moment doesn't exist.");
        try {
            List<?> l = json.readValue(mediaJson.get(0), List.class);
            for (Object o : l) {
                if (o instanceof Map<?, ?> x) media.delete(String.valueOf(x.get("url")), String.valueOf(x.get("format")));
            }
        } catch (Exception ignored) {
            // a broken media list: nothing on storage to clean up
        }
        jdbc.update("delete from moments where id = ?", id);
        return Map.of("id", id, "deleted", true);
    }
}
