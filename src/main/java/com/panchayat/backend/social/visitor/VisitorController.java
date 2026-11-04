package com.panchayat.backend.social.visitor;

import com.panchayat.backend.auth.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Visitors (signed in):
 *   POST /api/v1/visitors/{userId}             record that you opened their profile
 *   GET  /api/v1/visitors/me?kind=in|out       { stats{visitors,totalVisits,today,newCount}, logOn, logUntil, people[] }
 *   POST /api/v1/visitors/me/seen              clears the "new" marks
 *   GET  /api/v1/visitors/plans                visitor log plans (coins)
 *   POST /api/v1/visitors/plans/{id}/buy       { requestId } → { logUntil, balance }
 * Admin (SUPER_ADMIN, ADMIN):
 *   GET    /api/v1/admin/visitor-plans
 *   POST   /api/v1/admin/visitor-plans         { label, days, price, badge?, sortOrder?, active? }
 *   PUT    /api/v1/admin/visitor-plans/{id}
 *   DELETE /api/v1/admin/visitor-plans/{id}
 */
@RestController
public class VisitorController {

    private final VisitorService visitors;
    private final UserRepository users;

    public VisitorController(VisitorService visitors, UserRepository users) {
        this.visitors = visitors;
        this.users = users;
    }

    public record BuyRequest(String requestId) {
    }

    @PostMapping("/api/v1/visitors/{userId:[0-9]+}")
    public Map<String, Object> visit(@PathVariable Long userId, Authentication auth) {
        return visitors.visit(me(auth), userId);
    }

    @GetMapping("/api/v1/visitors/me")
    public Map<String, Object> mine(@RequestParam(defaultValue = "in") String kind, @RequestParam(defaultValue = "200") int limit, Authentication auth) {
        return visitors.list(me(auth), kind, limit);
    }

    @PostMapping("/api/v1/visitors/me/seen")
    public Map<String, Object> seen(Authentication auth) {
        return visitors.markSeen(me(auth));
    }

    @GetMapping("/api/v1/visitors/plans")
    public List<Map<String, Object>> plans(Authentication auth) {
        me(auth);
        return visitors.plans(false);
    }

    @PostMapping("/api/v1/visitors/plans/{id:[0-9]+}/buy")
    public Map<String, Object> buy(@PathVariable Long id, @RequestBody(required = false) BuyRequest body, Authentication auth) {
        return visitors.buy(me(auth), id, body == null ? null : body.requestId());
    }

    /* ---- admin ---- */

    @GetMapping("/api/v1/admin/visitor-plans")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','MODERATOR')")
    public List<Map<String, Object>> adminPlans() {
        return visitors.plans(true);
    }

    @PostMapping("/api/v1/admin/visitor-plans")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        return visitors.savePlan(null, body);
    }

    @PutMapping("/api/v1/admin/visitor-plans/{id:[0-9]+}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return visitors.savePlan(id, body);
    }

    @DeleteMapping("/api/v1/admin/visitor-plans/{id:[0-9]+}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        visitors.deletePlan(id);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handle(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("message", e.getReason() == null ? "That didn't work" : e.getReason()));
    }

    private Long me(Authentication auth) {
        if (auth == null || auth.getName() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in again.");
        return users.findByEmailIgnoreCase(auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Only user accounts have visitors.")).getId();
    }
}
