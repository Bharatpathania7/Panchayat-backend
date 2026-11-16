package com.panchayat.backend.music;

import com.panchayat.backend.auth.entity.User;
import com.panchayat.backend.auth.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Songs people in a room ask for: GET /song-requests · POST /song-requests {song} · DELETE /song-requests/{id}.
 * Anyone in the room adds; the owner and the admins put a song in the queue (and play it) or take it off.
 * Live updates: /topic/voice-rooms/{id}/songs.
 */
@RestController
@RequestMapping("/api/v1/voice-rooms/{roomId:[0-9]+}/song-requests")
public class RoomSongRequestController {

    private final RoomSongRequestService requests;
    private final UserRepository userRepository;

    public RoomSongRequestController(RoomSongRequestService requests, UserRepository userRepository) {
        this.requests = requests;
        this.userRepository = userRepository;
    }

    public record AddRequest(Map<String, Object> song) {
    }

    @GetMapping
    public Map<String, Object> list(@PathVariable Long roomId, Authentication auth) {
        me(auth);
        return requests.list(roomId);
    }

    @PostMapping
    public Map<String, Object> add(@PathVariable Long roomId, @RequestBody AddRequest b, Authentication auth) {
        if (b == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a song.");
        return requests.add(roomId, me(auth), b.song());
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> remove(@PathVariable Long roomId, @PathVariable String id, Authentication auth) {
        return requests.remove(roomId, me(auth), id);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handle(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("message", e.getReason() == null ? "That didn't work" : e.getReason()));
    }

    private Long me(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null || "anonymousUser".equals(auth.getPrincipal())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        Object p = auth.getPrincipal();
        if (p instanceof User u && u.getId() != null) return u.getId();
        return userRepository.findByEmailIgnoreCase(auth.getName()).map(User::getId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Only user accounts can use rooms"));
    }
}
