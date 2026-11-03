package com.panchayat.backend.gifts.controller;

import com.panchayat.backend.auth.entity.User;
import com.panchayat.backend.auth.repository.UserRepository;
import com.panchayat.backend.gifts.service.RoomGiftService;
import com.panchayat.backend.gifts.service.RoomGiftService.GiftSent;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Gifts in a voice room.
 *
 *   POST /api/v1/voice-rooms/{roomId}/gifts
 *        { giftId, toUserId, count, requestId }
 *   GET  /api/v1/voice-rooms/{roomId}/contribution?period=daily|weekly|total
 */
@RestController
@RequestMapping("/api/v1/voice-rooms/{roomId:[0-9]+}")
public class RoomGiftController {

    private final RoomGiftService gifts;
    private final UserRepository userRepository;

    public RoomGiftController(RoomGiftService gifts, UserRepository userRepository) {
        this.gifts = gifts;
        this.userRepository = userRepository;
    }

    public record SendGiftRequest(String giftId, Long toUserId, Integer count, String requestId, Boolean fromBag) {
    }

    @PostMapping("/gifts")
    public GiftSent send(@PathVariable Long roomId, @RequestBody SendGiftRequest body, Authentication auth) {
        if (body == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Gift details are required");
        return gifts.send(roomId, me(auth), body.giftId(), body.toUserId(), body.count() == null ? 1 : body.count(), body.requestId(),
                Boolean.TRUE.equals(body.fromBag()));
    }

    @GetMapping("/contribution")
    public Map<String, Object> contribution(@PathVariable Long roomId, @RequestParam(defaultValue = "daily") String period, Authentication auth) {
        return gifts.contribution(roomId, me(auth), period);
    }

    /** Readable messages ("Not enough coins…") instead of Spring's default error body. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handle(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode())
                .body(Map.of("message", e.getReason() == null ? "That didn't work" : e.getReason()));
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
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Only user accounts can send gifts"));
    }
}
