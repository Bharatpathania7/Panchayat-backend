package com.panchayat.backend.cp;

import com.panchayat.backend.auth.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CP (Couple Partner) API. Every endpoint needs a user JWT; the acting user is
 * always the one in the token. Errors are { message }. POSTs that spend coins
 * take an optional Idempotency-Key header (8–80 chars).
 *
 * See CpService for the rules, and the list of endpoints in the project notes.
 */
@RestController
public class CpController {

    private final CpService cp;
    private final UserRepository users;
    private final com.panchayat.backend.cache.ReadCache cache;

    public CpController(CpService cp, UserRepository users, com.panchayat.backend.cache.ReadCache cache) {
        this.cp = cp;
        this.users = users;
        this.cache = cache;
    }

    public record ProposeRequest(Long toUserId, String ringId, String message) {
    }

    public record RingBoxRequest(String ringId, String source) {
    }

    /* ---- rings ---- */

    @GetMapping("/api/v1/cp/rings")
    public ResponseEntity<String> rings(Authentication auth) {
        me(auth);
        // The catalogue is the same for everybody: kept for 15 s (a ring the admin adds shows up within that)
        return cache.respond("cp-rings", "all", java.time.Duration.ofSeconds(15), cp::rings);
    }

    @PostMapping("/api/v1/cp/rings/{ringId}/buy")
    public Map<String, Object> buy(@PathVariable String ringId, @RequestHeader(value = "Idempotency-Key", required = false) String key, Authentication auth) {
        return cp.buyRing(me(auth), ringId, key);
    }

    @GetMapping("/api/v1/cp/me/rings")
    public Map<String, Integer> bag(Authentication auth) {
        return cp.bag(me(auth));
    }

    /* ---- proposals ---- */

    @PostMapping("/api/v1/cp/proposals")
    public Map<String, Object> propose(@RequestBody ProposeRequest body, Authentication auth) {
        if (body == null) throw CpService.bad("Choose who to propose to and a ring");
        return cp.propose(me(auth), body.toUserId(), body.ringId(), body.message());
    }

    @GetMapping("/api/v1/cp/me/proposals")
    public Map<String, Object> proposals(Authentication auth) {
        return cp.myProposals(me(auth));
    }

    @PostMapping("/api/v1/cp/proposals/{id:[0-9]+}/accept")
    public Map<String, Object> accept(@PathVariable Long id, Authentication auth) {
        return cp.accept(me(auth), id);
    }

    @PostMapping("/api/v1/cp/proposals/{id:[0-9]+}/decline")
    public Map<String, Object> decline(@PathVariable Long id, Authentication auth) {
        return cp.decline(me(auth), id);
    }

    @PostMapping("/api/v1/cp/proposals/{id:[0-9]+}/cancel")
    public Map<String, Object> cancel(@PathVariable Long id, Authentication auth) {
        return cp.cancel(me(auth), id);
    }

    /* ---- couples ---- */

    /** Your couple, or null (JSON null body). */
    @GetMapping("/api/v1/cp/me")
    public ResponseEntity<Object> mine(Authentication auth) {
        return orNull(cp.mine(me(auth)));
    }

    @GetMapping("/api/v1/users/{userId:[0-9]+}/cp")
    public ResponseEntity<Object> of(@PathVariable Long userId, Authentication auth) {
        me(auth);
        return orNull(cp.of(userId));
    }

    @GetMapping("/api/v1/cp/me/charm-history")
    public List<Map<String, Object>> charmHistory(@RequestParam(defaultValue = "all") String filter, @RequestParam(defaultValue = "50") int limit, Authentication auth) {
        return cp.charmHistory(me(auth), filter, limit);
    }

    @GetMapping("/api/v1/cp/ranking")
    public List<Map<String, Object>> ranking(@RequestParam(defaultValue = "today") String period, @RequestParam(defaultValue = "50") int limit, Authentication auth) {
        me(auth);
        return cp.ranking(period, limit);
    }

    @PostMapping("/api/v1/cp/me/rings")
    public Map<String, Object> addRing(@RequestBody RingBoxRequest body, @RequestHeader(value = "Idempotency-Key", required = false) String key, Authentication auth) {
        if (body == null) throw CpService.bad("Choose a ring");
        return cp.addRing(me(auth), body.ringId(), body.source(), key);
    }

    @PutMapping(value = "/api/v1/cp/me/wallpaper", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> wallpaper(@RequestParam("file") MultipartFile file, Authentication auth) {
        return cp.setWallpaper(me(auth), file);
    }

    @DeleteMapping("/api/v1/cp/me/wallpaper")
    public Map<String, Object> deleteWallpaper(Authentication auth) {
        return cp.deleteWallpaper(me(auth));
    }

    /* ---- blessings ---- */

    @PostMapping("/api/v1/cp/{coupleId:[0-9]+}/blessings")
    public Map<String, Object> bless(@PathVariable Long coupleId, @RequestHeader(value = "Idempotency-Key", required = false) String key, Authentication auth) {
        return cp.bless(me(auth), coupleId, key);
    }

    @GetMapping("/api/v1/cp/{coupleId:[0-9]+}/blessings")
    public Map<String, Object> blessings(@PathVariable Long coupleId, @RequestParam(required = false) Long cursor, @RequestParam(defaultValue = "20") int limit, Authentication auth) {
        me(auth);
        return cp.blessings(coupleId, cursor, limit);
    }

    /* ---- ending ---- */

    @PostMapping("/api/v1/cp/me/end-request")
    public Map<String, Object> endRequest(Authentication auth) {
        return cp.requestEnd(me(auth));
    }

    @PostMapping("/api/v1/cp/me/end-request/cancel")
    public Map<String, Object> endCancel(Authentication auth) {
        return cp.cancelEnd(me(auth));
    }

    @PostMapping("/api/v1/cp/me/end-request/decline")
    public Map<String, Object> endDecline(Authentication auth) {
        return cp.declineEnd(me(auth));
    }

    @PostMapping("/api/v1/cp/me/end-request/accept")
    public Map<String, Object> endAccept(Authentication auth) {
        return cp.acceptEnd(me(auth));
    }

    /* ---- errors: { message } ---- */

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handle(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("message", e.getReason() == null ? "That didn't work" : e.getReason()));
    }

    @ExceptionHandler(org.springframework.dao.PessimisticLockingFailureException.class)
    public ResponseEntity<Map<String, String>> busy(org.springframework.dao.PessimisticLockingFailureException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "That changed at the same time. Try again"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> clash(DataIntegrityViolationException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "That changed at the same time. Try again"));
    }

    private Long me(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null || "anonymousUser".equals(auth.getName()))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in again");
        return users.findByEmailIgnoreCase(auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Only user accounts can have a CP")).getId();
    }

    /** A JSON null body (not an empty one) when there's no couple. */
    private static ResponseEntity<Object> orNull(Object body) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body != null ? body : tools.jackson.databind.node.NullNode.getInstance());
    }

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }
}
