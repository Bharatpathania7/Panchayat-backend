package com.panchayat.backend.dailygift;

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
 * Daily red packet and locked prize.
 *   GET  /api/v1/daily-gift                 what the app shows
 *   POST /api/v1/daily-gift/ping            the app is open (once a minute)
 *   POST /api/v1/daily-gift/claim           open today's red packet
 *   POST /api/v1/daily-gift/bonus/claim     take the locked prize once it has opened
 * Admin:
 *   GET  /api/v1/admin/manage/daily-gifts           settings and numbers
 *   PUT  /api/v1/admin/manage/daily-gifts/settings  { enabled, dailyCoins, bonusEnabled, bonusCoins, bonusDays, bonusActiveMinutes }
 */
@RestController
public class DailyGiftController {

    private final DailyGiftService gifts;
    private final UserRepository users;

    public DailyGiftController(DailyGiftService gifts, UserRepository users) {
        this.gifts = gifts;
        this.users = users;
    }

    public record SettingsBody(Boolean enabled, Long dailyCoins, Boolean bonusEnabled, Long bonusCoins, Integer bonusDays, Integer bonusActiveMinutes) {
    }

    @GetMapping("/api/v1/daily-gift")
    public Map<String, Object> state(Authentication auth) {
        return gifts.state(me(auth));
    }

    @PostMapping("/api/v1/daily-gift/ping")
    public Map<String, Object> ping(Authentication auth) {
        return gifts.ping(me(auth));
    }

    @PostMapping("/api/v1/daily-gift/claim")
    public Map<String, Object> claim(Authentication auth) {
        return gifts.claimDaily(me(auth));
    }

    @PostMapping("/api/v1/daily-gift/bonus/claim")
    public Map<String, Object> claimBonus(Authentication auth) {
        return gifts.claimBonus(me(auth));
    }

    @GetMapping("/api/v1/admin/manage/daily-gifts")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public Map<String, Object> admin() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("settings", gifts.settings());
        out.put("stats", gifts.stats());
        return out;
    }

    @PutMapping("/api/v1/admin/manage/daily-gifts/settings")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public Map<String, Object> save(@RequestBody SettingsBody b) {
        if (b == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nothing to save.");
        gifts.save(b.enabled(), b.dailyCoins(), b.bonusEnabled(), b.bonusCoins(), b.bonusDays(), b.bonusActiveMinutes());
        return admin();
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handle(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("message", e.getReason() == null ? "That didn't work" : e.getReason()));
    }

    private Long me(Authentication auth) {
        if (auth == null || auth.getName() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in again.");
        return users.findByEmailIgnoreCase(auth.getName()).orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Only user accounts can claim this.")).getId();
    }
}
