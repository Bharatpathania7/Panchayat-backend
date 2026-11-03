package com.panchayat.backend.rooms.controller;

import com.panchayat.backend.auth.entity.User;
import com.panchayat.backend.auth.repository.UserRepository;
import com.panchayat.backend.rooms.service.RoomMediaService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * The room's mode and its shared YouTube video.
 *
 *   GET    /api/v1/voice-rooms/{id}/media
 *   PUT    /api/v1/voice-rooms/{id}/mode           { mode: VOICE | WATCH | KHARIDARI }
 *   PUT    /api/v1/voice-rooms/{id}/video          { videoId, title }
 *   DELETE /api/v1/voice-rooms/{id}/video
 *   POST   /api/v1/voice-rooms/{id}/video/control  { action: play | pause | seek, position }
 */
@RestController
@RequestMapping("/api/v1/voice-rooms/{roomId:[0-9]+}")
public class RoomMediaController {

    private final RoomMediaService media;
    private final UserRepository userRepository;

    public RoomMediaController(RoomMediaService media, UserRepository userRepository) {
        this.media = media;
        this.userRepository = userRepository;
    }

    public record ModeBody(String mode) {
    }

    public record VideoBody(String videoId, String title) {
    }

    public record ControlBody(String action, Double position) {
    }

    @GetMapping("/media")
    public Map<String, Object> get(@PathVariable Long roomId, Authentication auth) {
        me(auth);
        return media.get(roomId);
    }

    @PutMapping("/mode")
    public Map<String, Object> mode(@PathVariable Long roomId, @RequestBody ModeBody body, Authentication auth) {
        return media.setMode(roomId, me(auth), body == null ? null : body.mode());
    }

    @PutMapping("/video")
    public Map<String, Object> video(@PathVariable Long roomId, @RequestBody VideoBody body, Authentication auth) {
        if (body == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "videoId is required");
        return media.setVideo(roomId, me(auth), body.videoId(), body.title());
    }

    @DeleteMapping("/video")
    public Map<String, Object> removeVideo(@PathVariable Long roomId, Authentication auth) {
        return media.removeVideo(roomId, me(auth));
    }

    @PostMapping("/video/control")
    public Map<String, Object> control(@PathVariable Long roomId, @RequestBody ControlBody body, Authentication auth) {
        if (body == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "action is required");
        return media.control(roomId, me(auth), body.action(), body.position());
    }

    private Long me(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null
                || "anonymousUser".equals(auth.getPrincipal())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        Object p = auth.getPrincipal();
        if (p instanceof User u && u.getId() != null) return u.getId();
        return userRepository.findByEmailIgnoreCase(auth.getName())
                .map(User::getId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Only user accounts can use rooms"));
    }
}
