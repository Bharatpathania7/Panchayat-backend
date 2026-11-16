package com.panchayat.backend.games.gaddar;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Gaon ka Gaddaar without a voice room: a table has its own code and can be opened to everyone.
 *   POST /api/v1/gaddar/tables              open a table (you are the host)        → { state, me, ... }
 *   GET  /api/v1/gaddar/tables              public tables waiting for players
 *   POST /api/v1/gaddar/tables/join {code}  join the table with that code
 * Once you are in, everything is on /api/v1/gaddar/t/{id}/... (the same calls a voice-room game has) and live on
 * /topic/gaddar/t/{id} (send to /app/gaddar/t/{id}, /app/gaddar/t/{id}/act and /app/gaddar/t/{id}/chat).
 */
@RestController
@RequestMapping("/api/v1/gaddar")
public class GaddarTablesController {

    private final GaddarService game;

    public GaddarTablesController(GaddarService game) {
        this.game = game;
    }

    public record JoinBody(String code) {
    }

    @PostMapping("/tables")
    public Map<String, Object> create(Authentication auth) {
        return game.createTable(email(auth));
    }

    @GetMapping("/tables")
    public List<Map<String, Object>> list(Authentication auth) {
        email(auth);
        return game.openTables();
    }

    @PostMapping("/tables/join")
    public Map<String, Object> join(@RequestBody(required = false) JoinBody body, Authentication auth) {
        return game.joinByCode(email(auth), body == null ? null : body.code());
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
