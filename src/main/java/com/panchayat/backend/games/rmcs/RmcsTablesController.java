package com.panchayat.backend.games.rmcs;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Raja Mantri Chor Sipahi played on its own, without a voice room (like Gaon ka Gaddaar).
 *   POST /api/v1/rmcs/tables              open a table (you get a code)
 *   GET  /api/v1/rmcs/tables              the open public tables
 *   POST /api/v1/rmcs/tables/join         { code }    or { id }
 * Everything else about a table is on /api/v1/rmcs/t/{id}/... (see RmcsController).
 */
@RestController
@RequestMapping("/api/v1/rmcs/tables")
public class RmcsTablesController {

    private final RmcsService game;

    public RmcsTablesController(RmcsService game) {
        this.game = game;
    }

    public record JoinBody(String code, Long id) {
    }

    @PostMapping
    public Map<String, Object> create(Authentication auth) {
        return game.createTable(email(auth));
    }

    @GetMapping
    public Map<String, Object> list(Authentication auth) {
        email(auth);
        List<Map<String, Object>> tables = game.openTables();
        return Map.of("tables", tables);
    }

    @PostMapping("/join")
    public Map<String, Object> join(@RequestBody(required = false) JoinBody b, Authentication auth) {
        String e = email(auth);
        if (b == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a code.");
        if (b.id() != null) return game.joinTableById(b.id(), e);
        return game.joinByCode(e, b.code());
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
