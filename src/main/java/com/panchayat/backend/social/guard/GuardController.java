package com.panchayat.backend.social.guard;

import com.panchayat.backend.admin.media.MediaUrls;
import com.panchayat.backend.auth.repository.UserRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * Guard: who stands behind someone.
 *   GET  /api/v1/guard/{userId|me}
 *     → { userId, total, protectedToday, guards: [{ userId, name, avatarUrl, charm, giftCharm, ringCharm, protects, guard }] }
 *     Each guardian's charm = the charm their gifts gave (coins ÷ 5) plus the charm of
 *     the rings they gave. Each protect adds PROTECT_GUARD. Sorted by guard, highest first.
 *   POST /api/v1/guard/{userId}/protect   once a day per person (not yourself)
 */
@RestController
@RequestMapping("/api/v1/guard")
public class GuardController {

    public static final int PROTECT_GUARD = 30;
    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    private final JdbcTemplate jdbc;
    private final UserRepository users;

    public GuardController(JdbcTemplate jdbc, UserRepository users) {
        this.jdbc = jdbc;
        this.users = users;
    }

    @GetMapping("/{who}")
    public Map<String, Object> guard(@PathVariable String who, Authentication auth) {
        Long me = me(auth);
        Long uid = "me".equals(who) ? me : parse(who);
        Map<Long, Map<String, Object>> by = new LinkedHashMap<>();
        // Gifts: charm is coins ÷ 5
        jdbc.query("select sender_id, sum(coins) / 5 as charm from room_gift_events where receiver_id = ? and sender_id <> ? group by sender_id",
                rs -> {
                    row(by, rs.getLong(1)).put("giftCharm", rs.getLong(2));
                }, uid, uid);
        // Rings
        jdbc.query("select from_id, sum(charm) from cp_ring_charm where to_id = ? group by from_id",
                rs -> {
                    row(by, rs.getLong(1)).put("ringCharm", rs.getLong(2));
                }, uid);
        // Protects
        jdbc.query("select guard_id, count(*) from user_protects where user_id = ? group by guard_id",
                rs -> {
                    row(by, rs.getLong(1)).put("protects", rs.getLong(2));
                }, uid);
        List<Map<String, Object>> guards = new ArrayList<>();
        long total = 0;
        for (Map.Entry<Long, Map<String, Object>> e : by.entrySet()) {
            Map<String, Object> g = e.getValue();
            long gift = (long) g.getOrDefault("giftCharm", 0L), ring = (long) g.getOrDefault("ringCharm", 0L), prot = (long) g.getOrDefault("protects", 0L);
            long charm = gift + ring;
            long score = charm + prot * PROTECT_GUARD;
            if (score <= 0) continue;
            Map<String, Object> u = jdbc.queryForList("select full_name, avatar_url from users where id = ?", e.getKey()).stream().findFirst().orElse(null);
            if (u == null) continue;
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("userId", String.valueOf(e.getKey()));
            out.put("name", u.get("full_name"));
            out.put("avatarUrl", MediaUrls.link((String) u.get("avatar_url")));
            out.put("charm", charm);
            out.put("giftCharm", gift);
            out.put("ringCharm", ring);
            out.put("protects", prot);
            out.put("guard", score);
            guards.add(out);
            total += score;
        }
        guards.sort((a, b) -> Long.compare((long) b.get("guard"), (long) a.get("guard")));
        Boolean today = jdbc.queryForObject("select count(*) > 0 from user_protects where guard_id = ? and user_id = ? and day = ?", Boolean.class, me, uid, Date.valueOf(LocalDate.now(ZONE)));
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("userId", String.valueOf(uid));
        res.put("total", total);
        res.put("protectedToday", Boolean.TRUE.equals(today));
        res.put("protectGuard", PROTECT_GUARD);
        res.put("guards", guards.size() > 100 ? guards.subList(0, 100) : guards);
        return res;
    }

    @PostMapping("/{userId:[0-9]+}/protect")
    public Map<String, Object> protect(@PathVariable Long userId, Authentication auth) {
        Long me = me(auth);
        if (me.equals(userId)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You can't protect yourself.");
        Integer exists = jdbc.queryForObject("select count(*) from users where id = ?", Integer.class, userId);
        if (exists == null || exists == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown user.");
        Integer blocked = jdbc.queryForObject("select count(*) from user_blocks where (blocker_id = ? and blocked_id = ?) or (blocker_id = ? and blocked_id = ?)", Integer.class, me, userId, userId, me);
        if (blocked != null && blocked > 0) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can't protect this person.");
        try {
            jdbc.update("insert into user_protects (guard_id, user_id, day) values (?, ?, ?)", me, userId, Date.valueOf(LocalDate.now(ZONE)));
        } catch (DuplicateKeyException twice) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You already protected them today. Come back tomorrow.");
        }
        return guard(String.valueOf(userId), auth);
    }

    private static Map<String, Object> row(Map<Long, Map<String, Object>> by, long id) {
        return by.computeIfAbsent(id, k -> new HashMap<>());
    }

    private Long parse(String who) {
        try {
            Long id = Long.parseLong(who);
            Integer n = jdbc.queryForObject("select count(*) from users where id = ?", Integer.class, id);
            if (n == null || n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown user.");
            return id;
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown user.");
        }
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
