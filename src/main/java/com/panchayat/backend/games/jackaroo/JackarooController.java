package com.panchayat.backend.games.jackaroo;

import com.panchayat.backend.auth.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Jackaroo in rooms (signed in, in the room):
 *   GET  /api/v1/voice-rooms/{roomId}/games/current   { game }  (with "you": your hand and legal moves)
 *   POST /api/v1/voice-rooms/{roomId}/games/jackaroo  open a table (or get the open one)
 *   POST /api/v1/games/{id}/sit     { seat? }          sit down / change seat
 *   POST /api/v1/games/{id}/leave
 *   POST /api/v1/games/{id}/start                      the opener, with 2 or 4 players
 *   POST /api/v1/games/{id}/cancel                     the opener
 *   POST /api/v1/games/{id}/play    { move }           index into you.legal
 *   POST /api/v1/games/{id}/burn    { card }           only when there's no legal move
 * Live: /topic/voice-rooms/{roomId}/game (the table), /user/queue/game (your hand).
 */
@RestController
public class JackarooController {

    private final JackarooService games;
    private final UserRepository users;

    public JackarooController(JackarooService games, UserRepository users) {
        this.games = games;
        this.users = users;
    }

    public record SitRequest(Integer seat) {
    }

    public record PlayRequest(Integer move) {
    }

    public record BurnRequest(Integer card) {
    }

    @GetMapping("/api/v1/voice-rooms/{roomId:[0-9]+}/games/current")
    public Map<String, Object> current(@PathVariable Long roomId, Authentication auth) {
        return games.current(roomId, me(auth));
    }

    @PostMapping("/api/v1/voice-rooms/{roomId:[0-9]+}/games/jackaroo")
    public Map<String, Object> open(@PathVariable Long roomId, Authentication auth) {
        return games.open(roomId, me(auth));
    }

    @GetMapping("/api/v1/games/{id:[0-9]+}")
    public Map<String, Object> get(@PathVariable Long id, Authentication auth) {
        return games.get(id, me(auth));
    }

    @PostMapping("/api/v1/games/{id:[0-9]+}/sit")
    public Map<String, Object> sit(@PathVariable Long id, @RequestBody(required = false) SitRequest body, Authentication auth) {
        return games.sit(id, me(auth), body == null ? null : body.seat());
    }

    @PostMapping("/api/v1/games/{id:[0-9]+}/leave")
    public Map<String, Object> leave(@PathVariable Long id, Authentication auth) {
        return games.leave(id, me(auth));
    }

    @PostMapping("/api/v1/games/{id:[0-9]+}/start")
    public Map<String, Object> start(@PathVariable Long id, Authentication auth) {
        return games.start(id, me(auth));
    }

    @PostMapping("/api/v1/games/{id:[0-9]+}/cancel")
    public Map<String, Object> cancel(@PathVariable Long id, Authentication auth) {
        return games.cancel(id, me(auth));
    }

    @PostMapping("/api/v1/games/{id:[0-9]+}/play")
    public Map<String, Object> play(@PathVariable Long id, @RequestBody PlayRequest body, Authentication auth) {
        if (body == null || body.move() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a move.");
        return games.play(id, me(auth), body.move());
    }

    @PostMapping("/api/v1/games/{id:[0-9]+}/burn")
    public Map<String, Object> burn(@PathVariable Long id, @RequestBody(required = false) BurnRequest body, Authentication auth) {
        return games.burn(id, me(auth), body == null || body.card() == null ? 0 : body.card());
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handle(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("message", e.getReason() == null ? "That didn't work" : e.getReason()));
    }

    private Long me(Authentication auth) {
        if (auth == null || auth.getName() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in again.");
        return users.findByEmailIgnoreCase(auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Only user accounts can play.")).getId();
    }
}
