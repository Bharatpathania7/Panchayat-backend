package com.panchayat.backend.social.follow;

import com.panchayat.backend.auth.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Friends (signed in):
 *   POST /api/v1/friends/{userId}/follow | unfollow   → status
 *   GET  /api/v1/friends/{userId}/status              { following, followsYou, friends, followers, followingCount, friendsCount }
 *   GET  /api/v1/friends?kind=friends|following|followers   { counts…, onlineCount, people[{…, online, room{name, game}}] }
 *   GET  /api/v1/friends/activity                     what friends did lately
 */
@RestController
public class FriendController {

    private final FriendService friends;
    private final UserRepository users;

    public FriendController(FriendService friends, UserRepository users) {
        this.friends = friends;
        this.users = users;
    }

    @PostMapping("/api/v1/friends/{userId:[0-9]+}/follow")
    public Map<String, Object> follow(@PathVariable Long userId, Authentication auth) {
        return friends.follow(me(auth), userId);
    }

    @PostMapping("/api/v1/friends/{userId:[0-9]+}/unfollow")
    public Map<String, Object> unfollow(@PathVariable Long userId, Authentication auth) {
        return friends.unfollow(me(auth), userId);
    }

    @GetMapping("/api/v1/friends/{userId:[0-9]+}/status")
    public Map<String, Object> status(@PathVariable Long userId, Authentication auth) {
        return friends.status(me(auth), userId);
    }

    @GetMapping("/api/v1/friends")
    public Map<String, Object> list(@RequestParam(defaultValue = "friends") String kind, @RequestParam(defaultValue = "200") int limit, Authentication auth) {
        return friends.list(me(auth), kind, limit);
    }

    @GetMapping("/api/v1/friends/activity")
    public List<Map<String, Object>> activity(@RequestParam(defaultValue = "40") int limit, Authentication auth) {
        return friends.activity(me(auth), limit);
    }

    private Long me(Authentication auth) {
        if (auth == null || auth.getName() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in again.");
        return users.findByEmailIgnoreCase(auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Only user accounts have friends.")).getId();
    }
}
