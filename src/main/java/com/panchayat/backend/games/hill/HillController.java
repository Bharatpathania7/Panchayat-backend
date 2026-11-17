package com.panchayat.backend.games.hill;

import com.panchayat.backend.auth.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pahadi Daud (solo hill climb).
 *   GET  /api/v1/games/hill            settings for this player (caps, coin values, best)
 *   POST /api/v1/games/hill/start      → { runId, ... }
 *   POST /api/v1/games/hill/finish     { runId, distance, coins } → { paid, balance, ... }
 * Admin: GET /api/v1/admin/manage/games/hill, PUT /api/v1/admin/manage/games/hill/settings { dailyCap, runCap, coinBig, coinSmall }
 */
@RestController
public class HillController {

    private final HillService hill;
    private final UserRepository users;

    public HillController(HillService hill, UserRepository users) {
        this.hill = hill;
        this.users = users;
    }

    public record FinishBody(Long runId, Integer distance, Long coins) {
    }

    public record SettingsBody(Long dailyCap, Long runCap, Integer coinBig, Integer coinSmall) {
    }

    @GetMapping("/api/v1/games/hill")
    public Map<String, Object> info(Authentication auth) {
        return hill.info(me(auth));
    }

    @PostMapping("/api/v1/games/hill/start")
    public Map<String, Object> start(Authentication auth) {
        return hill.start(me(auth));
    }

    @PostMapping("/api/v1/games/hill/finish")
    public Map<String, Object> finish(@RequestBody FinishBody b, Authentication auth) {
        if (b == null || b.runId() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing run.");
        return hill.finish(me(auth), b.runId(), b.distance() == null ? 0 : b.distance(), b.coins() == null ? 0 : b.coins());
    }

    @GetMapping("/api/v1/admin/manage/games/hill")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public Map<String, Object> admin() {
        Map<String, Object> out = new LinkedHashMap<>();
        HillService.Settings s = hill.settings();
        out.put("settings", Map.of("dailyCap", s.dailyCap(), "runCap", s.runCap(), "coinBig", s.coinBig(), "coinSmall", s.coinSmall()));
        out.put("stats", hill.stats());
        return out;
    }

    @PutMapping("/api/v1/admin/manage/games/hill/settings")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public Map<String, Object> save(@RequestBody SettingsBody b) {
        if (b == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nothing to save.");
        hill.save(b.dailyCap(), b.runCap(), b.coinBig(), b.coinSmall());
        return admin();
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handle(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("message", e.getReason() == null ? "That didn't work" : e.getReason()));
    }

    private Long me(Authentication auth) {
        if (auth == null || auth.getName() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in again.");
        return users.findByEmailIgnoreCase(auth.getName()).orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Only user accounts can play.")).getId();
    }
}
