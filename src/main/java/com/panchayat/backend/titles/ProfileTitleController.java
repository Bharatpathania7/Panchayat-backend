package com.panchayat.backend.titles;

import com.panchayat.backend.auth.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/** Profile titles for the app: see mine, wear, take off, buy, and see what someone else wears. */
@RestController
@RequestMapping("/api/v1/titles")
public class ProfileTitleController {

    private final ProfileTitleService titles;
    private final UserRepository users;

    public ProfileTitleController(ProfileTitleService titles, UserRepository users) {
        this.titles = titles;
        this.users = users;
    }

    @ExceptionHandler(ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<Map<String, String>> handle(ResponseStatusException e) {
        return org.springframework.http.ResponseEntity.status(e.getStatusCode()).body(Map.of("message", e.getReason() == null ? "That didn't work" : e.getReason()));
    }

    private Long me(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null || "anonymousUser".equals(auth.getName()))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in again");
        return users.findByEmailIgnoreCase(auth.getName()).orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Only user accounts can use titles")).getId();
    }

    @GetMapping("/mine")
    public Map<String, Object> mine(Authentication auth) {
        return titles.mine(me(auth));
    }

    @PostMapping("/{id:[0-9]+}/wear")
    public Map<String, Object> wear(@PathVariable Long id, Authentication auth) {
        return titles.wear(me(auth), id);
    }

    @DeleteMapping("/{id:[0-9]+}/wear")
    public Map<String, Object> takeOff(@PathVariable Long id, Authentication auth) {
        return titles.takeOff(me(auth), id);
    }

    @PostMapping("/{id:[0-9]+}/buy")
    public Map<String, Object> buy(@PathVariable Long id, Authentication auth) {
        return titles.buy(me(auth), id);
    }

    /** What a person wears, for their profile. */
    @GetMapping("/of/{userId:[0-9]+}")
    public List<Map<String, Object>> of(@PathVariable Long userId, Authentication auth) {
        me(auth);
        return titles.worn(userId);
    }
}
