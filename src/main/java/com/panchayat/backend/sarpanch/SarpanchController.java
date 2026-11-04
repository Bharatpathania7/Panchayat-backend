package com.panchayat.backend.sarpanch;

import com.panchayat.backend.admin.media.MediaUrls;
import com.panchayat.backend.auth.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.util.*;

/**
 * Sarpanch for users (signed in):
 *   GET /api/v1/sarpanch/profile                     the official account's page: profile, stats, notice board
 *   GET /api/v1/sarpanch/me/messages?category=&page=&size=   your Sarpanch messages (Notifications)
 *   GET /api/v1/sarpanch/announcements/latest        the newest announcement to everyone (Discover), or null
 *   GET /api/v1/sarpanch/announcements?limit=        recent announcements (the notice board)
 */
@RestController
@RequestMapping("/api/v1/sarpanch")
public class SarpanchController {

    private final SarpanchService sarpanch;
    private final UserRepository users;
    private final JdbcTemplate jdbc;

    public SarpanchController(SarpanchService sarpanch, UserRepository users, JdbcTemplate jdbc) {
        this.sarpanch = sarpanch;
        this.users = users;
        this.jdbc = jdbc;
    }

    @GetMapping("/profile")
    public Map<String, Object> profile(Authentication auth) {
        Long me = me(auth);
        Long id = sarpanch.sarpanchId();
        Map<String, Object> p = new LinkedHashMap<>(sarpanch.profile());
        p.put("publicId", jdbc.queryForObject("select coalesce(display_public_id, public_id) from users where id = ?", Long.class, id));
        p.put("followers", jdbc.queryForObject("select count(*) from user_follows where followee_id = ?", Long.class, id));
        p.put("following", 0);
        p.put("youFollow", Boolean.TRUE.equals(jdbc.queryForObject("select count(*) > 0 from user_follows where follower_id = ? and followee_id = ?", Boolean.class, me, id)));
        Long conv = jdbc.query("select c.id from chat_conversations c join chat_conversation_participants p on p.conversation_id = c.id where c.system_conversation and p.user_id = ? limit 1",
                rs -> rs.next() ? rs.getLong(1) : null, me);
        p.put("conversationId", conv == null ? null : String.valueOf(conv));
        p.put("notices", announcements(5));
        return p;
    }

    @GetMapping("/me/messages")
    public Map<String, Object> mine(@RequestParam(required = false) String category, @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size, Authentication auth) {
        return sarpanch.myMessages(me(auth), category, page, size);
    }

    @GetMapping("/announcements/latest")
    public ResponseEntity<Object> latest(Authentication auth) {
        me(auth);
        Map<String, Object> a = sarpanch.latestAnnouncement();
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(a != null ? a : tools.jackson.databind.node.NullNode.getInstance());
    }

    @GetMapping("/announcements")
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "10") int limit, Authentication auth) {
        me(auth);
        return announcements(limit);
    }

    private List<Map<String, Object>> announcements(int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        jdbc.query("select id, title, body, image_url, action_label, action_url, category, coalesce(finished_at, created_at) as at from sarpanch_broadcasts"
                        + " where status = 'SENT' and audience like '%\"ALL\"%' order by at desc limit ?",
                rs -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", String.valueOf(rs.getLong("id")));
                    m.put("title", rs.getString("title"));
                    m.put("body", rs.getString("body"));
                    m.put("imageUrl", MediaUrls.link(rs.getString("image_url")));
                    m.put("actionLabel", rs.getString("action_label"));
                    m.put("actionUrl", rs.getString("action_url"));
                    m.put("category", rs.getString("category"));
                    Timestamp at = rs.getTimestamp("at");
                    m.put("at", at == null ? null : at.toInstant().toString());
                    out.add(m);
                }, Math.max(1, Math.min(limit, 50)));
        return out;
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handle(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("message", e.getReason() == null ? "That didn't work" : e.getReason()));
    }

    private Long me(Authentication auth) {
        if (auth == null || auth.getName() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in again.");
        return users.findByEmailIgnoreCase(auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Sign in with a user account.")).getId();
    }
}
