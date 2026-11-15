package com.panchayat.backend.treasure;

import com.panchayat.backend.auth.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Room treasure ("Daily Task" in a voice room).
 *
 *   GET  /api/v1/room-treasure/me                 the boxes, the tasks and how long each box still needs
 *   POST /api/v1/room-treasure/claim  { boxId }   open a box that is ready (once a day)
 */
@RestController
@RequestMapping("/api/v1/room-treasure")
public class RoomTreasureController {

    private final RoomTreasureService treasure;
    private final UserRepository users;

    public RoomTreasureController(RoomTreasureService treasure, UserRepository users) {
        this.treasure = treasure;
        this.users = users;
    }

    public record Claim(String boxId) {
    }

    @GetMapping("/me")
    public Map<String, Object> me(Authentication auth) {
        return treasure.info(uid(auth));
    }

    @PostMapping("/claim")
    public Map<String, Object> claim(@RequestBody Claim body, Authentication auth) {
        long id;
        try {
            id = Long.parseLong(body == null || body.boxId() == null ? "" : body.boxId().trim());
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a box");
        }
        return treasure.claim(uid(auth), id);
    }

    private Long uid(Authentication auth) {
        if (auth == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in first");
        return users.findByEmailIgnoreCase(auth.getName()).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in first")).getId();
    }
}
