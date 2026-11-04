package com.panchayat.backend.social.invites;

import com.panchayat.backend.auth.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Invites (signed in):
 *   POST /api/v1/chat/conversations/{id}/invites  { kind: ROOM|GAME, roomId?, gameId? }  → { message, invite }
 *   POST /api/v1/voice-rooms/{roomId}/invite-code  → { invite: "inv:CODE" }  (join a password room with it, 24 h)
 *   GET  /api/v1/games/code/{code}                 → { gameId, game, roomId, roomName, status, invite? }
 */
@RestController
public class InviteController {

    private final InviteService invites;
    private final UserRepository users;

    public InviteController(InviteService invites, UserRepository users) {
        this.invites = invites;
        this.users = users;
    }

    public record InviteRequest(String kind, Long roomId, Long gameId) {
    }

    @PostMapping("/api/v1/chat/conversations/{id:[0-9]+}/invites")
    public Map<String, Object> send(@PathVariable Long id, @RequestBody InviteRequest body, Authentication auth) {
        if (body == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose what to invite to.");
        return invites.send(me(auth), id, body.kind(), body.roomId(), body.gameId());
    }

    @PostMapping("/api/v1/voice-rooms/{roomId:[0-9]+}/invite-code")
    public Map<String, Object> roomCode(@PathVariable Long roomId, Authentication auth) {
        return Map.of("invite", "inv:" + invites.roomInvite(roomId, me(auth)));
    }

    @GetMapping("/api/v1/games/code/{code:[A-Za-z0-9]{4,8}}")
    public Map<String, Object> byCode(@PathVariable String code, Authentication auth) {
        return invites.byCode(code, me(auth));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handle(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("message", e.getReason() == null ? "That didn't work" : e.getReason()));
    }

    private Long me(Authentication auth) {
        if (auth == null || auth.getName() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in again.");
        return users.findByEmailIgnoreCase(auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Only user accounts can invite.")).getId();
    }
}
