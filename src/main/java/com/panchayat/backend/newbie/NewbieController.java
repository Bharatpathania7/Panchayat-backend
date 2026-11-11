package com.panchayat.backend.newbie;

import com.panchayat.backend.auth.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Newbie Welcome Rooms for the app: the rooms at the top of Home, my newbie status, the welcome card and the extra seats. */
@RestController
@RequestMapping("/api/v1/newbie")
public class NewbieController {

    private final NewbieService newbie;
    private final UserRepository users;

    private final com.panchayat.backend.admin.media.AdminMediaService media;

    public NewbieController(NewbieService newbie, UserRepository users, com.panchayat.backend.admin.media.AdminMediaService media) {
        this.newbie = newbie;
        this.users = users;
        this.media = media;
    }

    @ExceptionHandler(ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<Map<String, String>> handle(ResponseStatusException e) {
        return org.springframework.http.ResponseEntity.status(e.getStatusCode()).body(Map.of("message", e.getReason() == null ? "That didn't work" : e.getReason()));
    }

    private Long me(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null || "anonymousUser".equals(auth.getName()))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in again");
        return users.findByEmailIgnoreCase(auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Only user accounts can use this")).getId();
    }

    /** The open welcome rooms, best first. */
    @GetMapping("/rooms")
    public List<Map<String, Object>> rooms(Authentication auth) {
        me(auth);
        return newbie.discover();
    }

    @GetMapping("/me")
    public Map<String, Object> mine(Authentication auth) {
        return newbie.me(me(auth));
    }

    /** The person has seen the welcome card: don't show it again. */
    @PostMapping("/me/welcome-seen")
    public Map<String, Object> seen(Authentication auth) {
        newbie.welcomeSeen(me(auth));
        return Map.of("ok", true);
    }

    /** Which of these users are newbies (for the NEWBIE badge). ids = internal user ids, comma separated. */
    @GetMapping("/check")
    public Map<String, Object> check(@RequestParam String ids, Authentication auth) {
        me(auth);
        List<Long> list = new ArrayList<>();
        for (String s : ids.split(",")) {
            try {
                if (!s.isBlank() && list.size() < 100) list.add(Long.parseLong(s.trim()));
            } catch (NumberFormatException ignored) {
                // skip
            }
        }
        Set<Long> found = newbie.newbies(list);
        return Map.of("newbies", found.stream().map(String::valueOf).toList());
    }

    public record SeatsBody(Boolean enabled) {
    }

    /** The host opens or closes the 6 extra welcome seats. */
    @PutMapping("/rooms/{roomId:[0-9]+}/extra-seats")
    public Map<String, Object> extraSeats(@PathVariable Long roomId, @RequestBody SeatsBody body, Authentication auth) {
        return newbie.setExtraSeats(roomId, me(auth), body != null && Boolean.TRUE.equals(body.enabled()), false);
    }

    /** The host (or a room admin) uploads a background picture (JPG or PNG) for the welcome room. */
    @PostMapping(value = "/rooms/{roomId:[0-9]+}/background", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> setBackground(@PathVariable Long roomId, @RequestParam("file") org.springframework.web.multipart.MultipartFile file, Authentication auth) {
        var up = media.upload(file, "newbie", Set.of("PNG", "JPG", "JPEG"));
        return newbie.setRoomBackground(roomId, me(auth), up.url());
    }

    /** Back to the admin's picture. */
    @DeleteMapping("/rooms/{roomId:[0-9]+}/background")
    public Map<String, Object> resetBackground(@PathVariable Long roomId, Authentication auth) {
        return newbie.setRoomBackground(roomId, me(auth), null);
    }
}
