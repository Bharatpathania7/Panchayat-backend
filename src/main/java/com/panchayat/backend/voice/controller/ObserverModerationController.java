package com.panchayat.backend.voice.controller;

import com.panchayat.backend.admin.users.AccountBanService;
import com.panchayat.backend.auth.repository.UserRepository;
import com.panchayat.backend.common.PublicIds;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The silent observer (a trusted staff account, invisible in rooms) bans an account from inside the room, without
 * the admin panel:
 *   GET  /api/v1/observer/me                           am I a silent observer (the app shows the ban button only then)
 *   POST /api/v1/voice-rooms/{roomId}/observer/ban     { user: 7-digit ID or account id, days: null = permanent, reason }
 * Only a silent-observer account that is in that room right now. Never staff or official accounts. Every ban is
 * written to silent_observer_audit and to the account's ban record with the observer's name.
 */
@RestController
public class ObserverModerationController {

    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final AccountBanService bans;

    public ObserverModerationController(JdbcTemplate jdbc, UserRepository users, AccountBanService bans) {
        this.jdbc = jdbc;
        this.users = users;
        this.bans = bans;
    }

    public record BanBody(String user, Integer days, String reason) {
    }

    @GetMapping("/api/v1/observer/me")
    public Map<String, Object> me(Authentication auth) {
        return Map.of("observer", observerId(auth, false) != null);
    }

    @PostMapping("/api/v1/voice-rooms/{roomId:[0-9]+}/observer/ban")
    @Transactional
    public Map<String, Object> ban(@PathVariable Long roomId, @RequestBody BanBody body, Authentication auth) {
        Long me = observerId(auth, true);
        Integer inRoom = jdbc.queryForObject("select count(*) from voice_room_participants where room_id = ? and user_id = ? and status = 'JOINED'", Integer.class, roomId, me);
        if (inRoom == null || inRoom == 0) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Join the room first.");
        if (body == null || body.user() == null || body.user().isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose who to ban.");
        if (body.days() != null && (body.days() < 1 || body.days() > 3650)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Days must be 1 to 3650, or empty for permanent.");
        String reason = body.reason() == null || body.reason().isBlank() ? "Banned by Panchayat safety team" : body.reason().strip();
        if (reason.length() > 200) reason = reason.substring(0, 200);
        Long target = findUser(body.user().strip());
        if (target.equals(me)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You can't ban yourself.");
        Map<String, Object> t = jdbc.queryForMap("select role, coalesce(official, false) as official, official_role, coalesce(silent_observer, false) as observer, full_name from users where id = ?", target);
        if (!"USER".equals(t.get("role")) || Boolean.TRUE.equals(t.get("official")) || t.get("official_role") != null || Boolean.TRUE.equals(t.get("observer"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Staff and official accounts can't be banned here.");
        }
        String observerName = jdbc.queryForObject("select coalesce(full_name, email) from users where id = ?", String.class, me);
        AccountBanService.Ban b = bans.ban(target, body.days(), reason, "observer:" + observerName);
        jdbc.update("insert into silent_observer_audit (user_id, room_id) values (?, ?)", me, roomId);
        // Out of the room at once
        jdbc.update("update voice_room_participants set status = 'LEFT', left_at = now() where room_id = ? and user_id = ? and status = 'JOINED'", roomId, target);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("banned", true);
        m.put("name", t.get("full_name"));
        m.put("permanent", b.permanent());
        m.put("until", b.until() == null ? null : b.until().toString());
        return m;
    }

    private Long observerId(Authentication auth, boolean required) {
        if (auth == null || auth.getName() == null) {
            if (required) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in first");
            return null;
        }
        Long id = users.findByEmailIgnoreCase(auth.getName()).map(u -> u.isSilentObserver() ? u.getId() : null).orElse(null);
        if (id == null && required) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the safety team can do this.");
        return id;
    }

    private Long findUser(String ref) {
        Long pub = PublicIds.parse(ref);
        List<Long> ids = pub != null ? jdbc.queryForList("select id from users where public_id = ? or display_public_id = ? limit 1", Long.class, pub, pub) : List.of();
        if (ids.isEmpty()) {
            try {
                ids = jdbc.queryForList("select id from users where id = ?", Long.class, Long.parseLong(ref));
            } catch (NumberFormatException e) {
                ids = List.of();
            }
        }
        if (ids.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No user has the ID " + ref + ".");
        return ids.get(0);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> refused(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("message", e.getReason() == null ? "That didn't work." : e.getReason()));
    }
}
