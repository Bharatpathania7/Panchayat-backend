package com.panchayat.backend.pk.controller;

import com.panchayat.backend.auction.service.RoomAuctionService;
import com.panchayat.backend.auth.entity.User;
import com.panchayat.backend.auth.repository.UserRepository;
import com.panchayat.backend.pk.service.RoomPkService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * PK battles and Kharidari auctions in a voice room.
 *
 * PK:      GET /pk · PUT /pk {mode, rule, minutes} · POST /pk/start · DELETE /pk · POST /pk/vote {side}
 * Auction: GET /auction · POST /auction/seat {userId?} · POST /auction/leave
 *          PUT /auction/declaration {relation, declaration} · PUT /auction/config {mode, offer, startGiftId}
 *          POST /auction/start · POST /auction/end
 * Live updates: /topic/voice-rooms/{id}/pk and /topic/voice-rooms/{id}/auction.
 */
@RestController
@RequestMapping("/api/v1/voice-rooms/{roomId:[0-9]+}")
public class RoomGamesController {

    private final RoomPkService pk;
    private final RoomAuctionService auction;
    private final UserRepository userRepository;

    public RoomGamesController(RoomPkService pk, RoomAuctionService auction, UserRepository userRepository) {
        this.pk = pk;
        this.auction = auction;
        this.userRepository = userRepository;
    }

    public record PkSetup(String mode, String rule, Integer minutes) {
    }

    public record PkVote(String side) {
    }

    public record Seat(Long userId) {
    }

    public record Declaration(String relation, String declaration) {
    }

    public record AuctionConfig(String mode, String offer, String startGiftId) {
    }

    // ---- PK ----

    @GetMapping("/pk")
    public Map<String, Object> getPk(@PathVariable Long roomId, Authentication auth) {
        me(auth);
        return pk.get(roomId);
    }

    @PutMapping("/pk")
    public Map<String, Object> setupPk(@PathVariable Long roomId, @RequestBody PkSetup b, Authentication auth) {
        if (b == null) throw bad();
        return pk.setup(roomId, me(auth), b.mode(), b.rule(), b.minutes());
    }

    @PostMapping("/pk/start")
    public Map<String, Object> startPk(@PathVariable Long roomId, Authentication auth) {
        return pk.start(roomId, me(auth));
    }

    @DeleteMapping("/pk")
    public Map<String, Object> closePk(@PathVariable Long roomId, Authentication auth) {
        return pk.close(roomId, me(auth));
    }

    @PostMapping("/pk/vote")
    public Map<String, Object> vote(@PathVariable Long roomId, @RequestBody PkVote b, Authentication auth) {
        return pk.vote(roomId, me(auth), b == null ? null : b.side());
    }

    // ---- Kharidari ----

    @GetMapping("/auction")
    public Map<String, Object> getAuction(@PathVariable Long roomId, Authentication auth) {
        me(auth);
        return auction.get(roomId);
    }

    @PostMapping("/auction/seat")
    public Map<String, Object> sit(@PathVariable Long roomId, @RequestBody(required = false) Seat b, Authentication auth) {
        return auction.sit(roomId, me(auth), b == null ? null : b.userId());
    }

    @PostMapping("/auction/leave")
    public Map<String, Object> leave(@PathVariable Long roomId, Authentication auth) {
        return auction.leave(roomId, me(auth));
    }

    @PutMapping("/auction/declaration")
    public Map<String, Object> declare(@PathVariable Long roomId, @RequestBody Declaration b, Authentication auth) {
        if (b == null) throw bad();
        return auction.declare(roomId, me(auth), b.relation(), b.declaration());
    }

    @PutMapping("/auction/config")
    public Map<String, Object> configure(@PathVariable Long roomId, @RequestBody AuctionConfig b, Authentication auth) {
        if (b == null) throw bad();
        return auction.configure(roomId, me(auth), b.mode(), b.offer(), b.startGiftId());
    }

    @PostMapping("/auction/start")
    public Map<String, Object> start(@PathVariable Long roomId, Authentication auth) {
        return auction.start(roomId, me(auth));
    }

    @PostMapping("/auction/end")
    public Map<String, Object> end(@PathVariable Long roomId, Authentication auth) {
        return auction.endNow(roomId, me(auth));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handle(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode())
                .body(Map.of("message", e.getReason() == null ? "That didn't work" : e.getReason()));
    }

    private static ResponseStatusException bad() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "A request body is required");
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
