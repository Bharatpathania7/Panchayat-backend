package com.panchayat.backend.voice.controller;

import com.panchayat.backend.auth.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Emotes in rooms: an animated emote over your seat that everyone sees.
 *   POST /api/v1/voice-rooms/{roomId}/emotes  { emote }
 * Live: /topic/voice-rooms/{roomId}/emote { userId, emote, result?, at }.
 * Game emotes get their result from the server so nobody can fake a roll:
 * "dice" → 1..6, "rps" → rock|paper|scissors, "coin" → heads|tails, "lucky" → 0..99.
 */
@RestController
public class RoomEmoteController {

    /** Emotes the app knows how to draw (plain emoji and the animated set). */
    private static final Set<String> GAME = Set.of("dice", "rps", "coin", "lucky");
    private static final int MAX_LEN = 16;
    private static final SecureRandom RNG = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final SimpMessagingTemplate messaging;

    public RoomEmoteController(JdbcTemplate jdbc, UserRepository users, SimpMessagingTemplate messaging) {
        this.jdbc = jdbc;
        this.users = users;
        this.messaging = messaging;
    }

    public record EmoteRequest(String emote) {
    }

    @PostMapping("/api/v1/voice-rooms/{roomId:[0-9]+}/emotes")
    public Map<String, Object> send(@PathVariable Long roomId, @RequestBody EmoteRequest body, Authentication auth) {
        if (auth == null || auth.getName() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in first");
        Long me = users.findByEmailIgnoreCase(auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in first")).getId();
        String emote = body == null || body.emote() == null ? "" : body.emote().strip();
        if (emote.isEmpty() || emote.length() > MAX_LEN || emote.chars().anyMatch(c -> c < 0x20))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pick an emote.");
        Integer joined = jdbc.queryForObject(
                "select count(*) from voice_room_participants p join voice_rooms v on v.id = p.room_id where p.room_id = ? and p.user_id = ? and p.status = 'JOINED' and v.status = 'ACTIVE'",
                Integer.class, roomId, me);
        if (joined == null || joined == 0) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Join the room first.");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userId", String.valueOf(me));
        out.put("emote", emote);
        if (GAME.contains(emote)) {
            out.put("result", switch (emote) {
                case "dice" -> String.valueOf(1 + RNG.nextInt(6));
                case "rps" -> new String[]{"rock", "paper", "scissors"}[RNG.nextInt(3)];
                case "coin" -> RNG.nextBoolean() ? "heads" : "tails";
                default -> String.valueOf(RNG.nextInt(100));
            });
        }
        out.put("at", System.currentTimeMillis());
        messaging.convertAndSend("/topic/voice-rooms/" + roomId + "/emote", (Object) out);
        return out;
    }
}
