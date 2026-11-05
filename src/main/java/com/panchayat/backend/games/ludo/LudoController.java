package com.panchayat.backend.games.ludo;

import com.panchayat.backend.auth.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Ludo (signed in, in the room):
 *   GET  /api/v1/voice-rooms/{roomId}/ludo            { game }  (with "you": your seat, canRoll, movable tokens)
 *   POST /api/v1/voice-rooms/{roomId}/ludo  { stake } open a table (or get the open one)
 *   GET  /api/v1/ludo/{id}
 *   POST /api/v1/ludo/{id}/sit | /leave | /start | /cancel | /roll
 *   POST /api/v1/ludo/{id}/move   { token }  0..3, one of you.movable
 *   POST /api/v1/ludo/{id}/chat   { text?, emote? }
 * Live: /topic/ludo/{id} (the table), /topic/ludo/{id}/chat (chat and emotes).
 */
@RestController
public class LudoController {

    private final LudoService ludo;
    private final UserRepository users;

    public LudoController(LudoService ludo, UserRepository users) {
        this.ludo = ludo;
        this.users = users;
    }

    public record OpenRequest(Long stake) {
    }

    public record MoveRequest(Integer token) {
    }

    public record ChatRequest(String text, String emote) {
    }

    @GetMapping("/api/v1/voice-rooms/{roomId:[0-9]+}/ludo")
    public Map<String, Object> current(@PathVariable Long roomId, Authentication auth) {
        return ludo.current(roomId, me(auth));
    }

    @PostMapping("/api/v1/voice-rooms/{roomId:[0-9]+}/ludo")
    public Map<String, Object> open(@PathVariable Long roomId, @RequestBody(required = false) OpenRequest body, Authentication auth) {
        return ludo.open(roomId, me(auth), body == null ? null : body.stake());
    }

    @GetMapping("/api/v1/ludo/{id:[0-9]+}")
    public Map<String, Object> get(@PathVariable Long id, Authentication auth) {
        return ludo.view(id, me(auth));
    }

    @PostMapping("/api/v1/ludo/{id:[0-9]+}/sit")
    public Map<String, Object> sit(@PathVariable Long id, Authentication auth) {
        return ludo.sit(id, me(auth));
    }

    @PostMapping("/api/v1/ludo/{id:[0-9]+}/leave")
    public Map<String, Object> leave(@PathVariable Long id, Authentication auth) {
        return ludo.leave(id, me(auth));
    }

    @PostMapping("/api/v1/ludo/{id:[0-9]+}/start")
    public Map<String, Object> start(@PathVariable Long id, Authentication auth) {
        return ludo.start(id, me(auth));
    }

    @PostMapping("/api/v1/ludo/{id:[0-9]+}/cancel")
    public Map<String, Object> cancel(@PathVariable Long id, Authentication auth) {
        return ludo.cancel(id, me(auth));
    }

    @PostMapping("/api/v1/ludo/{id:[0-9]+}/roll")
    public Map<String, Object> roll(@PathVariable Long id, Authentication auth) {
        return ludo.roll(id, me(auth));
    }

    @PostMapping("/api/v1/ludo/{id:[0-9]+}/move")
    public Map<String, Object> move(@PathVariable Long id, @RequestBody MoveRequest body, Authentication auth) {
        if (body == null || body.token() == null || body.token() < 0 || body.token() > 3) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pick a token.");
        return ludo.move(id, me(auth), body.token());
    }

    @PostMapping("/api/v1/ludo/{id:[0-9]+}/chat")
    public Map<String, Object> chat(@PathVariable Long id, @RequestBody ChatRequest body, Authentication auth) {
        if (body == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Say something.");
        return ludo.chat(id, me(auth), body.text(), body.emote());
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handle(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("message", e.getReason() == null ? "That didn't work" : e.getReason()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> state(IllegalStateException e) {
        return ResponseEntity.status(409).body(Map.of("message", e.getMessage() == null ? "That can't be done right now" : e.getMessage()));
    }

    private Long me(Authentication auth) {
        if (auth == null || auth.getName() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in again.");
        return users.findByEmailIgnoreCase(auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Only user accounts can play.")).getId();
    }
}
