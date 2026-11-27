package com.panchayat.backend.games.duo;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Duo Dash for the app:
 *   POST /api/v1/duo/session {code}        a ticket for the game server + a voice token for the private "duo-CODE" channel
 *   GET  /api/v1/duo/leaderboard?period=   couples by furthest level, stars, coins (all | week)
 *   GET  /api/v1/duo/me                    your couples
 *   POST/GET/DELETE /api/v1/duo/match      singles: find a partner of the other gender, check, stop
 * And for the Duo Dash game server only (same machine, shared secret):
 *   POST /api/v1/duo/internal/result       a finished level, with both players' tickets
 *   POST /api/v1/duo/internal/pair         are these two CP partners (then they wear their ring)?
 *   POST /api/v1/duo/internal/propose      a ring proposal from the game, accepted there: becomes a real Panchayat CP
 */
@RestController
@RequestMapping("/api/v1/duo")
public class DuoController {

    private final DuoService duo;
    private final DuoMatchService match;
    private final JdbcTemplate jdbc;

    public DuoController(DuoService duo, DuoMatchService match, JdbcTemplate jdbc) {
        this.duo = duo;
        this.match = match;
        this.jdbc = jdbc;
    }

    public record MatchBody(String gender) {
    }

    /** Singles: look for a partner of the other gender (POST), check (GET), stop looking (DELETE). */
    @PostMapping("/match")
    public Map<String, Object> matchJoin(@RequestBody(required = false) MatchBody b, Authentication auth) {
        return match.join(userId(auth), b == null ? null : b.gender());
    }

    @GetMapping("/match")
    public Map<String, Object> matchStatus(Authentication auth) {
        return match.status(userId(auth));
    }

    @DeleteMapping("/match")
    public Map<String, Object> matchLeave(Authentication auth) {
        match.leave(userId(auth));
        return Map.of("state", "idle");
    }

    public record SessionBody(String code) {
    }

    @PostMapping("/session")
    public Map<String, Object> session(@RequestBody(required = false) SessionBody b, Authentication auth) {
        return duo.session(userId(auth), b == null ? null : b.code());
    }

    @GetMapping("/leaderboard")
    public Map<String, Object> leaderboard(@RequestParam(defaultValue = "all") String period, Authentication auth) {
        userId(auth);
        return Map.of("period", "week".equalsIgnoreCase(period) ? "week" : "all", "couples", duo.leaderboard(period, 50));
    }

    @GetMapping("/me")
    public Map<String, Object> me(Authentication auth) {
        return Map.of("couples", duo.mine(userId(auth)));
    }

    @PostMapping("/internal/result")
    public Map<String, Object> result(@RequestBody DuoService.Result r, @RequestHeader(value = "X-Duo-Secret", required = false) String secret, HttpServletRequest req) {
        String ip = req.getRemoteAddr();
        boolean local = "127.0.0.1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip);
        if (!local || !duo.secretMatches(secret)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed");
        return duo.record(r);
    }

    @PostMapping("/internal/pair")
    public Map<String, Object> pair(@RequestBody DuoService.PairBody b, @RequestHeader(value = "X-Duo-Secret", required = false) String secret, HttpServletRequest req) {
        String ip = req.getRemoteAddr();
        boolean local = "127.0.0.1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip);
        if (!local || !duo.secretMatches(secret)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed");
        return duo.pair(b);
    }

    @PostMapping("/internal/propose")
    public Map<String, Object> propose(@RequestBody DuoService.ProposeBody b, @RequestHeader(value = "X-Duo-Secret", required = false) String secret, HttpServletRequest req) {
        String ip = req.getRemoteAddr();
        boolean local = "127.0.0.1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip);
        if (!local || !duo.secretMatches(secret)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed");
        return duo.propose(b);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handle(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("message", e.getReason() == null ? "That didn't work" : e.getReason()));
    }

    private long userId(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null || "anonymousUser".equals(auth.getPrincipal())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in again.");
        }
        List<Long> ids = jdbc.queryForList("select id from users where lower(email) = lower(?)", Long.class, auth.getName());
        if (ids.isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in again.");
        return ids.get(0);
    }
}
