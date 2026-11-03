package com.panchayat.backend.tags.controller;

import com.panchayat.backend.auth.entity.User;
import com.panchayat.backend.auth.repository.UserRepository;
import com.panchayat.backend.tags.service.UserTagService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * BFF tags (shown on profiles as badges).
 *
 *   GET    /api/v1/tags/me
 *   GET    /api/v1/tags/user/{userId}
 *   DELETE /api/v1/tags/{tagId}
 */
@RestController
@RequestMapping("/api/v1/tags")
public class UserTagController {

    private final UserTagService tags;
    private final UserRepository userRepository;

    public UserTagController(UserTagService tags, UserRepository userRepository) {
        this.tags = tags;
        this.userRepository = userRepository;
    }

    @GetMapping("/me")
    public List<Map<String, Object>> mine(Authentication auth) {
        return tags.list(me(auth));
    }

    @GetMapping("/user/{userId:[0-9]+}")
    public List<Map<String, Object>> of(@PathVariable Long userId, Authentication auth) {
        me(auth);
        return tags.list(userId);
    }

    @DeleteMapping("/{tagId:[0-9]+}")
    public ResponseEntity<Void> breakTag(@PathVariable Long tagId, Authentication auth) {
        tags.breakTag(tagId, me(auth));
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handle(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode())
                .body(Map.of("message", e.getReason() == null ? "That didn't work" : e.getReason()));
    }

    private Long me(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null
                || "anonymousUser".equals(auth.getPrincipal())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        Object p = auth.getPrincipal();
        if (p instanceof User u && u.getId() != null) return u.getId();
        return userRepository.findByEmailIgnoreCase(auth.getName())
                .map(User::getId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Only user accounts have tags"));
    }
}
