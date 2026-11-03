package com.panchayat.backend.rooms.controller;

import com.panchayat.backend.auth.entity.User;
import com.panchayat.backend.auth.repository.UserRepository;
import com.panchayat.backend.rooms.service.RoomSettingsService;
import com.panchayat.backend.rooms.service.RoomSettingsService.SettingsUpdate;
import com.panchayat.backend.voice.dto.VoiceRoomResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Room settings, password check, cover picture, and joining / following a room.
 * Every change answers with the updated room and broadcasts it to
 * /topic/voice-rooms/{id}/info.
 */
@RestController
@RequestMapping("/api/v1/voice-rooms")
public class RoomSettingsController {

    private final RoomSettingsService settings;
    private final UserRepository userRepository;

    public RoomSettingsController(RoomSettingsService settings, UserRepository userRepository) {
        this.settings = settings;
        this.userRepository = userRepository;
    }

    // ---- Settings (owner and admins) ----

    @PatchMapping("/{roomId:[0-9]+}/settings")
    public VoiceRoomResponse update(@PathVariable Long roomId, @RequestBody SettingsUpdate body, Authentication auth) {
        return settings.update(roomId, me(auth), body);
    }

    @GetMapping("/{roomId:[0-9]+}/settings/private")
    public Map<String, Object> privateSettings(@PathVariable Long roomId, Authentication auth) {
        return settings.privateSettings(roomId, me(auth));
    }

    @PostMapping(value = "/{roomId:[0-9]+}/cover", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public VoiceRoomResponse uploadCover(@PathVariable Long roomId, @RequestParam("file") MultipartFile file, Authentication auth) {
        return settings.uploadCover(roomId, me(auth), file);
    }

    // ---- Password ----

    public record PasswordBody(String password) {
    }

    /** 200 { ok: true } when right; 423 when wrong; 429 after too many wrong tries. */
    @PostMapping("/{roomId:[0-9]+}/password-check")
    public ResponseEntity<Map<String, Object>> checkPassword(@PathVariable Long roomId, @RequestBody PasswordBody body, Authentication auth) {
        boolean ok = settings.checkPassword(roomId, me(auth), body == null ? null : body.password());
        return ok
                ? ResponseEntity.ok(Map.of("ok", true))
                : ResponseEntity.status(HttpStatus.LOCKED).body(Map.of("ok", false, "message", "Wrong password. Try again."));
    }

    // ---- Join (membership) and follow ----

    @PutMapping("/{roomId:[0-9]+}/membership")
    public VoiceRoomResponse join(@PathVariable Long roomId, Authentication auth) {
        return settings.setMember(roomId, me(auth), true);
    }

    @DeleteMapping("/{roomId:[0-9]+}/membership")
    public VoiceRoomResponse leaveMembership(@PathVariable Long roomId, Authentication auth) {
        return settings.setMember(roomId, me(auth), false);
    }

    @PutMapping("/{roomId:[0-9]+}/follow")
    public VoiceRoomResponse follow(@PathVariable Long roomId, Authentication auth) {
        return settings.setFollowing(roomId, me(auth), true);
    }

    @DeleteMapping("/{roomId:[0-9]+}/follow")
    public VoiceRoomResponse unfollow(@PathVariable Long roomId, Authentication auth) {
        return settings.setFollowing(roomId, me(auth), false);
    }

    /** { joined: [roomId…], followed: [roomId…] } for the signed-in person. */
    @GetMapping("/me/relations")
    public Map<String, List<String>> relations(Authentication auth) {
        return settings.relations(me(auth));
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
