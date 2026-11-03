package com.panchayat.backend.rooms.controller;

import com.panchayat.backend.auth.entity.User;
import com.panchayat.backend.auth.repository.UserRepository;
import com.panchayat.backend.rooms.dto.RoomStateResponse;
import com.panchayat.backend.rooms.service.RoomStateService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Seats, mic queue, admins, kick and ban for a voice room.
 * Every change answers with the new state and broadcasts it to
 * /topic/voice-rooms/{id}/state.
 */
@RestController
@RequestMapping("/api/v1/voice-rooms/{roomId:[0-9]+}")
public class RoomStateController {

    private final RoomStateService state;
    private final UserRepository userRepository;

    public RoomStateController(RoomStateService state, UserRepository userRepository) {
        this.state = state;
        this.userRepository = userRepository;
    }

    @GetMapping("/state")
    public RoomStateResponse get(@PathVariable Long roomId, Authentication auth) {
        me(auth);
        return state.getState(roomId);
    }

    // ---- Your seat ----

    @PostMapping("/seats/{index:[0-9]+}/take")
    public RoomStateResponse take(@PathVariable Long roomId, @PathVariable int index, Authentication auth) {
        return state.takeSeat(roomId, me(auth), index);
    }

    @PostMapping("/seats/leave")
    public RoomStateResponse leave(@PathVariable Long roomId, Authentication auth) {
        return state.leaveSeat(roomId, me(auth));
    }

    @PostMapping("/mic-queue/cancel")
    public RoomStateResponse cancel(@PathVariable Long roomId, Authentication auth) {
        return state.cancelMicRequest(roomId, me(auth));
    }

    // ---- Owner and admins ----

    @PostMapping("/mic-queue/{userId:[0-9]+}/approve")
    public RoomStateResponse approve(@PathVariable Long roomId, @PathVariable Long userId, Authentication auth) {
        return state.approveMicRequest(roomId, me(auth), userId);
    }

    @PostMapping("/mic-queue/{userId:[0-9]+}/decline")
    public RoomStateResponse decline(@PathVariable Long roomId, @PathVariable Long userId, Authentication auth) {
        return state.declineMicRequest(roomId, me(auth), userId);
    }

    @PutMapping("/mic-order")
    public RoomStateResponse micOrder(@PathVariable Long roomId, @RequestBody OnOff body, Authentication auth) {
        return state.setMicOrder(roomId, me(auth), body.required());
    }

    @PutMapping("/seats/{index:[0-9]+}/closed")
    public RoomStateResponse close(@PathVariable Long roomId, @PathVariable int index, @RequestBody OnOff body, Authentication auth) {
        return state.setSeatClosed(roomId, me(auth), index, body.required());
    }

    @PutMapping("/seats/{index:[0-9]+}/muted")
    public RoomStateResponse mute(@PathVariable Long roomId, @PathVariable int index, @RequestBody OnOff body, Authentication auth) {
        return state.setSeatMuted(roomId, me(auth), index, body.required());
    }

    @PostMapping("/seats/invite")
    public RoomStateResponse invite(@PathVariable Long roomId, @RequestBody Target body, Authentication auth) {
        return state.inviteToSeat(roomId, me(auth), body.requiredUser(), body.seat());
    }

    @PostMapping("/seats/invite/accept")
    public RoomStateResponse acceptInvite(@PathVariable Long roomId, Authentication auth) {
        return state.acceptSeatInvite(roomId, me(auth));
    }

    @PostMapping("/seats/invite/decline")
    public RoomStateResponse declineInvite(@PathVariable Long roomId, Authentication auth) {
        return state.declineSeatInvite(roomId, me(auth));
    }

    @PostMapping("/seats/invite/cancel")
    public RoomStateResponse cancelInvite(@PathVariable Long roomId, @RequestBody Target body, Authentication auth) {
        return state.cancelSeatInvite(roomId, me(auth), body.requiredUser());
    }

    @PostMapping("/seats/remove")
    public RoomStateResponse removeFromMic(@PathVariable Long roomId, @RequestBody Target body, Authentication auth) {
        return state.removeFromMic(roomId, me(auth), body.requiredUser());
    }

    @PutMapping("/admins/{userId:[0-9]+}")
    public RoomStateResponse setAdmin(@PathVariable Long roomId, @PathVariable Long userId, @RequestBody OnOff body, Authentication auth) {
        return state.setAdmin(roomId, me(auth), userId, body.required());
    }

    @PostMapping("/kick")
    public RoomStateResponse kick(@PathVariable Long roomId, @RequestBody Target body, Authentication auth) {
        return state.kick(roomId, me(auth), body.requiredUser());
    }

    @PostMapping("/bans")
    public RoomStateResponse ban(@PathVariable Long roomId, @RequestBody Target body, Authentication auth) {
        return state.ban(roomId, me(auth), body.requiredUser());
    }

    @DeleteMapping("/bans/{userId:[0-9]+}")
    public RoomStateResponse unban(@PathVariable Long roomId, @PathVariable Long userId, Authentication auth) {
        return state.unban(roomId, me(auth), userId);
    }

    @GetMapping("/bans")
    public List<Map<String, Object>> bans(@PathVariable Long roomId, Authentication auth) {
        return state.listBans(roomId, me(auth));
    }

    // ---- Bodies ----

    public record OnOff(Boolean on) {
        boolean required() {
            if (on == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "\"on\" is required");
            return on;
        }
    }

    public record Target(Long userId, Integer seat) {
        Long requiredUser() {
            if (userId == null || userId <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "\"userId\" is required");
            return userId;
        }
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
