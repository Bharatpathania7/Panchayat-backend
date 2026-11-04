package com.panchayat.backend.gifts.controller;

import com.panchayat.backend.admin.media.MediaUrls;
import com.panchayat.backend.auth.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * The gift wall: what someone has received (room and direct gifts).
 *   GET /api/v1/gifts/received/{userId|me}
 *   → { userId, totalGifts, totalCoins, gifts: { giftId: count }, topSenders: [{ userId, name, avatarUrl, coins }] }
 */
@RestController
@RequestMapping("/api/v1/gifts/received")
public class GiftsReceivedController {

    private final JdbcTemplate jdbc;
    private final UserRepository users;

    public GiftsReceivedController(JdbcTemplate jdbc, UserRepository users) {
        this.jdbc = jdbc;
        this.users = users;
    }

    @GetMapping("/{who}")
    public Map<String, Object> received(@PathVariable String who, Authentication auth) {
        if (auth == null || auth.getName() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in again.");
        Long uid;
        if ("me".equals(who)) {
            uid = users.findByEmailIgnoreCase(auth.getName()).orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Sign in with a user account.")).getId();
        } else {
            try {
                uid = Long.parseLong(who);
            } catch (NumberFormatException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown user.");
            }
            Integer n = jdbc.queryForObject("select count(*) from users where id = ?", Integer.class, uid);
            if (n == null || n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown user.");
        }
        Map<String, Long> gifts = new LinkedHashMap<>();
        long[] totals = {0, 0};
        jdbc.query("select gift_id, sum(gift_count) as n, sum(coins) as c from room_gift_events where receiver_id = ? group by gift_id order by sum(coins) desc",
                rs -> {
                    gifts.put(rs.getString("gift_id"), rs.getLong("n"));
                    totals[0] += rs.getLong("n");
                    totals[1] += rs.getLong("c");
                }, uid);
        List<Map<String, Object>> top = new ArrayList<>();
        jdbc.query("select e.sender_id, u.full_name, u.avatar_url, sum(e.coins) as c from room_gift_events e join users u on u.id = e.sender_id"
                        + " where e.receiver_id = ? group by e.sender_id, u.full_name, u.avatar_url order by c desc limit 10",
                rs -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("userId", String.valueOf(rs.getLong("sender_id")));
                    m.put("name", rs.getString("full_name"));
                    m.put("avatarUrl", MediaUrls.link(rs.getString("avatar_url")));
                    m.put("coins", rs.getLong("c"));
                    top.add(m);
                }, uid);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userId", String.valueOf(uid));
        out.put("totalGifts", totals[0]);
        out.put("totalCoins", totals[1]);
        out.put("gifts", gifts);
        out.put("topSenders", top);
        return out;
    }

    @ExceptionHandler(ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<Map<String, String>> handle(ResponseStatusException e) {
        return org.springframework.http.ResponseEntity.status(e.getStatusCode()).body(Map.of("message", e.getReason() == null ? "That didn't work" : e.getReason()));
    }
}
