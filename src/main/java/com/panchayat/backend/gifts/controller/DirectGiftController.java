package com.panchayat.backend.gifts.controller;

import com.panchayat.backend.auth.repository.UserRepository;
import com.panchayat.backend.gifts.service.DirectGiftService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Gifts outside rooms (signed in):
 *   POST /api/v1/users/{userId}/gifts         { giftId, count, requestId, context: PROFILE|TAG|CP|MOMENT|CHAT }
 *   GET  /api/v1/users/{userId}/gifts/with-me  recent gifts between you and them (both ways)
 *   GET  /api/v1/users/{userId}/gifts/received recent gifts they received
 */
@RestController
public class DirectGiftController {

    private final DirectGiftService gifts;
    private final UserRepository users;

    public DirectGiftController(DirectGiftService gifts, UserRepository users) {
        this.gifts = gifts;
        this.users = users;
    }

    public record SendRequest(String giftId, Integer count, String requestId, String context) {
    }

    @PostMapping("/api/v1/users/{userId:[0-9]+}/gifts")
    public Map<String, Object> send(@PathVariable Long userId, @RequestBody SendRequest body, Authentication auth) {
        if (body == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a gift");
        return gifts.send(me(auth), userId, body.giftId(), body.count() == null ? 1 : body.count(), body.requestId(), body.context());
    }

    @GetMapping("/api/v1/users/{userId:[0-9]+}/gifts/with-me")
    public List<Map<String, Object>> withMe(@PathVariable Long userId, @RequestParam(defaultValue = "20") int limit, Authentication auth) {
        return gifts.between(me(auth), userId, limit);
    }

    @GetMapping("/api/v1/users/{userId:[0-9]+}/gifts/received")
    public List<Map<String, Object>> received(@PathVariable Long userId, @RequestParam(defaultValue = "20") int limit) {
        return gifts.received(userId, limit);
    }

    private Long me(Authentication auth) {
        return users.findByEmailIgnoreCase(auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in again.")).getId();
    }
}
