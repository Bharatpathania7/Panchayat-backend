package com.panchayat.backend.redpacket;

import com.panchayat.backend.auth.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Red packets in rooms (signed in):
 *   POST /api/v1/voice-rooms/{roomId}/red-packets   { amount, cover, delaySeconds }  drop one
 *   GET  /api/v1/voice-rooms/{roomId}/red-packets/current                          the one raining now
 *   POST /api/v1/red-packets/{packetId}/grab                                        grab a share
 */
@RestController
public class RoomRedPacketController {

    private final RoomRedPacketService packets;
    private final UserRepository users;

    public RoomRedPacketController(RoomRedPacketService packets, UserRepository users) {
        this.packets = packets;
        this.users = users;
    }

    public record DropRequest(Long amount, String cover, Integer delaySeconds) {
    }

    @PostMapping("/api/v1/voice-rooms/{roomId:[0-9]+}/red-packets")
    public Map<String, Object> drop(@PathVariable Long roomId, @RequestBody DropRequest body, Authentication auth) {
        if (body == null || body.amount() == null || body.delaySeconds() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pick the amount and when it rains");
        }
        return packets.drop(roomId, me(auth), body.amount(), body.cover(), body.delaySeconds());
    }

    @GetMapping("/api/v1/voice-rooms/{roomId:[0-9]+}/red-packets/current")
    public Map<String, Object> current(@PathVariable Long roomId, Authentication auth) {
        return packets.current(roomId, me(auth));
    }

    @PostMapping("/api/v1/red-packets/{packetId:[0-9]+}/grab")
    public Map<String, Object> grab(@PathVariable Long packetId, Authentication auth) {
        return packets.grab(packetId, me(auth));
    }

    private Long me(Authentication auth) {
        if (auth == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in first");
        return users.findByEmailIgnoreCase(auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in first"))
                .getId();
    }
}
