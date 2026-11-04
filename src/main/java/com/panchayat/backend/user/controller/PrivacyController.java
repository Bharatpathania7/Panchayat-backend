package com.panchayat.backend.user.controller;

import com.panchayat.backend.auth.entity.User;
import com.panchayat.backend.auth.repository.UserRepository;
import com.panchayat.backend.chat.service.ChatSocialService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Privacy choices (signed in):
 *   GET /api/v1/users/me/privacy            { showOnline }
 *   PUT /api/v1/users/me/privacy            { showOnline }
 * With showOnline false nobody sees you as online, nor your last seen time,
 * nor which room you're in (Friends activity).
 */
@RestController
public class PrivacyController {

    private final UserRepository users;
    private final JdbcTemplate jdbc;
    private final ChatSocialService social;

    public PrivacyController(UserRepository users, JdbcTemplate jdbc, ChatSocialService social) {
        this.users = users;
        this.jdbc = jdbc;
        this.social = social;
    }

    public record PrivacyRequest(Boolean showOnline) {
    }

    @GetMapping("/api/v1/users/me/privacy")
    public Map<String, Object> get(Authentication auth) {
        User u = me(auth);
        Boolean hidden = jdbc.queryForObject("select hide_online from users where id = ?", Boolean.class, u.getId());
        return Map.of("showOnline", !Boolean.TRUE.equals(hidden));
    }

    @PutMapping("/api/v1/users/me/privacy")
    public Map<String, Object> put(@RequestBody PrivacyRequest body, Authentication auth) {
        User u = me(auth);
        if (body == null || body.showOnline() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Say whether to show your online status.");
        jdbc.update("update users set hide_online = ? where id = ?", !body.showOnline(), u.getId());
        social.announcePresence(u);
        return Map.of("showOnline", body.showOnline());
    }

    private User me(Authentication auth) {
        return users.findByEmailIgnoreCase(auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in again."));
    }
}
