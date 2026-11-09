package com.panchayat.backend.parivar;

import com.panchayat.backend.auth.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/** Parivar for the app: discover, create, join, members, treasury, ranking. Every rule is checked in ParivarService. */
@RestController
@RequestMapping("/api/v1/parivar")
public class ParivarController {

    private final ParivarService parivar;
    private final UserRepository users;

    public ParivarController(ParivarService parivar, UserRepository users) {
        this.parivar = parivar;
        this.users = users;
    }

    @ExceptionHandler(ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<Map<String, String>> handle(ResponseStatusException e) {
        return org.springframework.http.ResponseEntity.status(e.getStatusCode()).body(Map.of("message", e.getReason() == null ? "That didn't work" : e.getReason()));
    }

    @ExceptionHandler({org.springframework.dao.PessimisticLockingFailureException.class, org.springframework.dao.DataIntegrityViolationException.class})
    public org.springframework.http.ResponseEntity<Map<String, String>> clash(RuntimeException e) {
        return org.springframework.http.ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "That changed at the same time. Try again."));
    }

    private Long me(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null || "anonymousUser".equals(auth.getName()))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in again");
        return users.findByEmailIgnoreCase(auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Only user accounts can use Parivar")).getId();
    }

    public record DonateRequest(Long amount) {
    }

    @GetMapping("/config")
    public Map<String, Object> config(Authentication auth) {
        return parivar.config(me(auth));
    }

    @GetMapping("/me")
    public Map<String, Object> mine(Authentication auth) {
        return parivar.mine(me(auth));
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String q, @RequestParam(defaultValue = "popular") String sort,
                                    @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, Authentication auth) {
        return parivar.list(me(auth), q, sort, page, size);
    }

    @GetMapping("/ranking")
    public Map<String, Object> ranking(@RequestParam(defaultValue = "weekly") String period, Authentication auth) {
        return parivar.ranking(me(auth), period);
    }

    /** The tag (sign, emblem, colour) to show beside a person's name, or an empty answer. */
    @GetMapping("/tag/{userId:[0-9]+}")
    public Map<String, Object> tag(@PathVariable Long userId, Authentication auth) {
        me(auth);
        Map<String, Object> t = parivar.tagOf(userId);
        return t == null ? Map.of() : t;
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody ParivarService.CreateRequest body, @RequestHeader(value = "Idempotency-Key", required = false) String key, Authentication auth) {
        if (body == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Fill in the Parivar's details.");
        return parivar.create(me(auth), key, body);
    }

    @GetMapping("/{id:[0-9]+}")
    public Map<String, Object> profile(@PathVariable Long id, Authentication auth) {
        return parivar.profile(me(auth), id);
    }

    @PutMapping("/{id:[0-9]+}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody ParivarService.UpdateRequest body, Authentication auth) {
        return parivar.update(me(auth), id, body);
    }

    @PostMapping("/{id:[0-9]+}/join")
    public Map<String, Object> join(@PathVariable Long id, Authentication auth) {
        return parivar.join(me(auth), id);
    }

    @PostMapping("/{id:[0-9]+}/withdraw")
    public Map<String, Object> withdraw(@PathVariable Long id, Authentication auth) {
        return parivar.withdraw(me(auth), id);
    }

    @PostMapping("/leave")
    public Map<String, Object> leave(Authentication auth) {
        return parivar.leave(me(auth));
    }

    @GetMapping("/{id:[0-9]+}/applications")
    public List<Map<String, Object>> applications(@PathVariable Long id, Authentication auth) {
        return parivar.applications(me(auth), id);
    }

    @PostMapping("/{id:[0-9]+}/applications/{appId:[0-9]+}/accept")
    public Map<String, Object> accept(@PathVariable Long id, @PathVariable Long appId, Authentication auth) {
        return parivar.decide(me(auth), id, appId, true);
    }

    @PostMapping("/{id:[0-9]+}/applications/{appId:[0-9]+}/reject")
    public Map<String, Object> reject(@PathVariable Long id, @PathVariable Long appId, Authentication auth) {
        return parivar.decide(me(auth), id, appId, false);
    }

    @PostMapping("/{id:[0-9]+}/members/{userId:[0-9]+}/remove")
    public Map<String, Object> remove(@PathVariable Long id, @PathVariable Long userId, Authentication auth) {
        return parivar.removeMember(me(auth), id, userId);
    }

    @PutMapping("/{id:[0-9]+}/members/{userId:[0-9]+}/role")
    public Map<String, Object> role(@PathVariable Long id, @PathVariable Long userId, @RequestBody ParivarService.RoleRequest body, Authentication auth) {
        return parivar.setRole(me(auth), id, userId, body == null ? null : body.role());
    }

    @PostMapping("/{id:[0-9]+}/donate")
    public Map<String, Object> donate(@PathVariable Long id, @RequestBody DonateRequest body, @RequestHeader(value = "Idempotency-Key", required = false) String key, Authentication auth) {
        return parivar.donate(me(auth), id, body == null || body.amount() == null ? 0 : body.amount(), key);
    }

    @GetMapping("/{id:[0-9]+}/funds")
    public List<Map<String, Object>> funds(@PathVariable Long id, Authentication auth) {
        return parivar.fundsHistory(me(auth), id);
    }

    @GetMapping("/{id:[0-9]+}/activity")
    public List<Map<String, Object>> activity(@PathVariable Long id, Authentication auth) {
        return parivar.activityFeed(me(auth), id);
    }
}
