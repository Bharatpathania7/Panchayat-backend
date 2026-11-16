package com.panchayat.backend.games.rmcs;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Raja Mantri Chor Sipahi at a voice room's table.
 *   GET  /api/v1/voice-rooms/{id}/rmcs            the table as this player sees it
 *   POST /sit | /stand | /start | /lobby | /size {size} | /pick {target} | /chat {text|emote}
 */
@RestController
@RequestMapping({"/api/v1/voice-rooms/{roomId:[0-9]+}/rmcs", "/api/v1/rmcs/t/{roomId:[0-9]+}"})
public class RmcsController {

    private final RmcsService game;

    public RmcsController(RmcsService game) {
        this.game = game;
    }

    public record SizeBody(Integer size) {
    }

    public record PickBody(Long target) {
    }

    public record ChatBody(String text, String emote) {
    }

    @GetMapping
    public Map<String, Object> view(@PathVariable Long roomId, Authentication auth) {
        return game.view(roomId, email(auth));
    }

    @PostMapping("/sit")
    public Map<String, Object> sit(@PathVariable Long roomId, Authentication auth) {
        return game.sit(roomId, email(auth));
    }

    public record PublicBody(Boolean isPublic) {
    }

    @PostMapping("/public")
    public Map<String, Object> pub(@PathVariable Long roomId, @RequestBody(required = false) PublicBody b, Authentication auth) {
        return game.setPublic(roomId, email(auth), b == null || b.isPublic() == null || b.isPublic());
    }

    @PostMapping("/stand")
    public Map<String, Object> stand(@PathVariable Long roomId, Authentication auth) {
        return game.stand(roomId, email(auth));
    }

    @PostMapping("/start")
    public Map<String, Object> start(@PathVariable Long roomId, Authentication auth) {
        return game.begin(roomId, email(auth));
    }

    @PostMapping("/lobby")
    public Map<String, Object> lobby(@PathVariable Long roomId, Authentication auth) {
        return game.toLobby(roomId, email(auth));
    }

    @PostMapping("/size")
    public Map<String, Object> size(@PathVariable Long roomId, @RequestBody(required = false) SizeBody b, Authentication auth) {
        return game.setSize(roomId, email(auth), b == null || b.size() == null ? 4 : b.size());
    }

    @PostMapping("/pick")
    public Map<String, Object> pick(@PathVariable Long roomId, @RequestBody(required = false) PickBody b, Authentication auth) {
        return game.pick(roomId, email(auth), b == null ? null : b.target());
    }

    @PostMapping("/chat")
    public Map<String, Object> chat(@PathVariable Long roomId, @RequestBody(required = false) ChatBody b, Authentication auth) {
        return game.chat(roomId, email(auth), b == null ? null : b.text(), b == null ? null : b.emote());
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handle(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("message", e.getReason() == null ? "That didn't work" : e.getReason()));
    }

    private static String email(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null || "anonymousUser".equals(auth.getPrincipal())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in again.");
        }
        return auth.getName();
    }
}
