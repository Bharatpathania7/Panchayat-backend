package com.panchayat.backend.push;

import com.panchayat.backend.auth.entity.User;
import com.panchayat.backend.auth.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/** The Android app tells the server which phone to send notifications to. */
@RestController
@RequestMapping("/api/v1/push")
public class PushController {

    private final PushService push;
    private final UserRepository users;

    public PushController(PushService push, UserRepository users) {
        this.push = push;
        this.users = users;
    }

    public record TokenBody(String token, String platform) {
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handle(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("message", e.getReason() == null ? "That didn't work" : e.getReason()));
    }

    private Long me(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null || "anonymousUser".equals(auth.getPrincipal())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        Object p = auth.getPrincipal();
        if (p instanceof User u && u.getId() != null) return u.getId();
        return users.findByEmailIgnoreCase(auth.getName()).map(User::getId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Only user accounts can get notifications"));
    }

    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody TokenBody body, Authentication auth) {
        push.register(me(auth), body == null ? null : body.token(), body == null ? null : body.platform());
        return Map.of("ok", true);
    }

    @PostMapping("/unregister")
    public Map<String, Object> unregister(@RequestBody TokenBody body, Authentication auth) {
        push.unregister(me(auth), body == null ? null : body.token());
        return Map.of("ok", true);
    }

    /** Sends a test notification to the person's own phones (works even when they are online). */
    @PostMapping("/test")
    public Map<String, Object> test(Authentication auth) {
        int n = push.forceToUser(me(auth), "Panchayat", "Notifications are on for this phone.", Map.of("type", "TEST"));
        return Map.of("sentTo", n);
    }
}
