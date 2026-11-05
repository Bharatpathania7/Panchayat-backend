package com.panchayat.backend.user.controller;

import com.panchayat.backend.auth.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

/**
 * Gift-set pieces you wear, so everyone sees them on your profile and in rooms:
 *   PUT /api/v1/users/me/gift-set-wear  { frame?, bubble?, entry?, page? }  (a set id, or null to take it off)
 * A set counts as yours once you've sent its gift (e.g. Storm Guardian).
 * Your profile and others' view of it carry it as giftSetWear.
 */
@RestController
public class GiftSetWearController {

    /** Gift set id → the gift whose sending unlocks it. */
    public static final Map<String, String> SETS = Map.of("storm-guardian", "storm-guardian");
    private static final List<String> PIECES = List.of("frame", "bubble", "entry", "page");

    /** Gift sets (Storm Guardian) give no frame, bubble, entry or profile page effect. */
    public static final boolean GIFT_SET_COSMETICS = false;

    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final ObjectMapper json = new ObjectMapper();

    public GiftSetWearController(JdbcTemplate jdbc, UserRepository users) {
        this.jdbc = jdbc;
        this.users = users;
    }

    @PutMapping("/api/v1/users/me/gift-set-wear")
    public Map<String, Object> wear(@RequestBody Map<String, Object> body, Authentication auth) {
        if (auth == null || auth.getName() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in again.");
        Long me = users.findByEmailIgnoreCase(auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Only user accounts can wear sets.")).getId();
        // Gift sets no longer give cosmetics: nothing is saved, and anything worn is cleared
        if (!GIFT_SET_COSMETICS) {
            jdbc.update("update users set gift_set_wear = null where id = ?", me);
            return Map.of("giftSetWear", Map.of());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (String piece : PIECES) {
            Object v = body == null ? null : body.get(piece);
            if (v == null || String.valueOf(v).isBlank()) continue;
            String set = String.valueOf(v);
            String gift = SETS.get(set);
            if (gift == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown set.");
            Integer sent = jdbc.queryForObject("select count(*) from room_gift_events where sender_id = ? and gift_id = ?", Integer.class, me, gift);
            if (sent == null || sent == 0) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Send the gift first to unlock this set.");
            out.put(piece, set);
        }
        jdbc.update("update users set gift_set_wear = ? where id = ?", out.isEmpty() ? null : json.writeValueAsString(out), me);
        return Map.of("giftSetWear", out);
    }

    /** What someone wears (for profile responses). */
    public static Map<String, Object> read(JdbcTemplate jdbc, Long userId) {
        if (!GIFT_SET_COSMETICS) return Map.of();
        String raw = jdbc.query("select gift_set_wear from users where id = ?", rs -> rs.next() ? rs.getString(1) : null, userId);
        if (raw == null || raw.isBlank()) return Map.of();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = new ObjectMapper().readValue(raw, Map.class);
            return m;
        } catch (RuntimeException e) {
            return Map.of();
        }
    }
}
