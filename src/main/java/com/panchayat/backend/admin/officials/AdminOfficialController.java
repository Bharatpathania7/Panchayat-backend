package com.panchayat.backend.admin.officials;

import com.panchayat.backend.common.PublicIds;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Official IDs (admin panel): accounts numbered 001, 002, 003, ... with a blue tick that anyone can message.
 * Staff run one by signing in to the app with its email (Google or the email code).
 *
 *   GET    /api/v1/admin/officials                 the list
 *   POST   /api/v1/admin/officials                 { name, email, bio }  a new official account
 *   POST   /api/v1/admin/officials/make            { user }  an existing account (internal id or public ID) becomes official
 *   DELETE /api/v1/admin/officials/{userId}        no longer official (the account stays)
 */
@RestController
@RequestMapping("/api/v1/admin/officials")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
public class AdminOfficialController {

    private final JdbcTemplate jdbc;

    public AdminOfficialController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record CreateRequest(String name, String email, String bio) {}

    public record MakeRequest(String user) {}

    @GetMapping
    public List<Map<String, Object>> list() {
        return jdbc.query(
                "select u.id, u.official_code, u.full_name, u.email, u.bio, u.avatar_url, coalesce(u.display_public_id, u.public_id) as public_id, u.created_at,"
                        + " (select count(*) from chat_conversation_participants p where p.user_id = u.id) as chats"
                        + " from users u where u.official_code is not null order by u.official_code",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("userId", String.valueOf(rs.getLong("id")));
                    m.put("code", code(rs.getInt("official_code")));
                    m.put("name", rs.getString("full_name"));
                    m.put("email", rs.getString("email"));
                    m.put("bio", rs.getString("bio"));
                    m.put("avatarUrl", com.panchayat.backend.admin.media.MediaUrls.protectedLink(rs.getString("avatar_url")));
                    m.put("publicId", rs.getLong("public_id"));
                    m.put("createdAt", rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toInstant().toString());
                    m.put("chats", rs.getLong("chats"));
                    return m;
                });
    }

    @PostMapping
    @Transactional
    public Map<String, Object> create(@RequestBody CreateRequest body) {
        String name = body == null || body.name() == null ? "" : body.name().strip();
        String email = body == null || body.email() == null ? "" : body.email().strip().toLowerCase(Locale.ROOT);
        String bio = body == null || body.bio() == null || body.bio().isBlank() ? null : body.bio().strip();
        if (name.length() < 2 || name.length() > 40) throw bad("Give the official ID a name of 2 to 40 letters.");
        if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) throw bad("Give an email you can sign in with (Google or the email code).");
        if (bio != null && bio.length() > 120) bio = bio.substring(0, 120);
        Integer taken = jdbc.queryForObject("select count(*) from users where lower(email) = ?", Integer.class, email);
        if (taken != null && taken > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "That email already has an account. Use \"Make an account official\" with its ID instead.");
        int next = nextCode();
        Long id = jdbc.queryForObject(
                "insert into users (email, full_name, bio, email_verified, profile_completed, official_code) values (?, ?, ?, true, true, ?) returning id",
                Long.class, email, name, bio, next);
        return Map.of("userId", String.valueOf(id), "code", code(next), "publicId", PublicIds.user(id));
    }

    @PostMapping("/make")
    @Transactional
    public Map<String, Object> make(@RequestBody MakeRequest body) {
        String q = body == null || body.user() == null ? "" : body.user().strip();
        if (!q.matches("\\d{1,12}")) throw bad("Type the account's ID.");
        Long n = Long.parseLong(q);
        List<Long> ids = jdbc.queryForList("select id from users where public_id = ? or display_public_id = ? or id = ? order by (public_id = ? or display_public_id = ?) desc limit 1",
                Long.class, n, n, n, n, n);
        if (ids.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No account with that ID.");
        Long id = ids.get(0);
        Integer have = jdbc.queryForObject("select official_code from users where id = ?", Integer.class, id);
        if (have != null) return Map.of("userId", String.valueOf(id), "code", code(have));
        int next = nextCode();
        jdbc.update("update users set official_code = ? where id = ?", next, id);
        return Map.of("userId", String.valueOf(id), "code", code(next));
    }

    @DeleteMapping("/{userId:[0-9]+}")
    public Map<String, Object> remove(@PathVariable Long userId) {
        int n = jdbc.update("update users set official_code = null where id = ? and official_code is not null", userId);
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That account is not an official ID.");
        return Map.of("userId", String.valueOf(userId), "official", false);
    }

    /** The next free number: 1, 2, 3, ... (shown as 001, 002, 003). */
    private int nextCode() {
        Integer max = jdbc.queryForObject("select coalesce(max(official_code), 0) from users", Integer.class);
        return (max == null ? 0 : max) + 1;
    }

    public static String code(int n) {
        return String.format("%03d", n);
    }

    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }
}
