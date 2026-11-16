package com.panchayat.backend.games.gaddar;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.Map;

/**
 * Gaon ka Gaddaar, inside a voice room.
 *   GET  /api/v1/voice-rooms/{id}/gaddar          { map, state, me?, snap? }
 *   POST /api/v1/voice-rooms/{id}/gaddar/join | leave | start | lobby
 *   POST /api/v1/voice-rooms/{id}/gaddar/vote     { target }  (a player id; 0 or none = skip)
 * Live: send /app/voice-rooms/{id}/gaddar { dx, dy, work } and /app/voice-rooms/{id}/gaddar.act { a };
 * listen on /topic/voice-rooms/{id}/gaddar (state, snap) and /user/queue/gaddar (your role and tasks).
 */
@RestController
@RequestMapping({"/api/v1/voice-rooms/{roomId:[0-9]+}/gaddar", "/api/v1/gaddar/t/{roomId:[0-9]+}"})
public class GaddarController {

    private final GaddarService game;

    public GaddarController(GaddarService game) {
        this.game = game;
    }

    public record LookBody(Integer hat, Integer suit, Integer acc) {
    }

    public record VoteBody(Long target) {
    }

    public record SettingsBody(String map, Integer gaddars, Integer killCd, Boolean isPublic) {
    }

    @GetMapping
    public Map<String, Object> get(@PathVariable Long roomId, Authentication auth) {
        return game.get(roomId, email(auth));
    }

    @PostMapping("/join")
    public Map<String, Object> join(@PathVariable Long roomId, Authentication auth) {
        return game.join(roomId, email(auth));
    }

    @PostMapping("/leave")
    public Map<String, Object> leave(@PathVariable Long roomId, Authentication auth) {
        return game.leave(roomId, email(auth));
    }

    @PostMapping("/start")
    public Map<String, Object> start(@PathVariable Long roomId, Authentication auth) {
        return game.start(roomId, email(auth));
    }

    @PostMapping("/lobby")
    public Map<String, Object> lobby(@PathVariable Long roomId, Authentication auth) {
        return game.backToLobby(roomId, email(auth));
    }

    @PostMapping("/settings")
    public Map<String, Object> settings(@PathVariable Long roomId, @RequestBody(required = false) SettingsBody body, Authentication auth) {
        if (body == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a setting.");
        return game.settings(roomId, email(auth), body.map(), body.gaddars(), body.killCd(), body.isPublic());
    }

    @GetMapping("/map/{mapId:[a-z]+}")
    public Map<String, Object> map(@PathVariable Long roomId, @PathVariable String mapId, Authentication auth) {
        email(auth);
        return game.mapJson(mapId);
    }

    @PostMapping("/look")
    public Map<String, Object> look(@PathVariable Long roomId, @RequestBody(required = false) LookBody body, Authentication auth) {
        return game.look(roomId, email(auth), body == null ? null : body.hat(), body == null ? null : body.suit(), body == null ? null : body.acc());
    }

    @PostMapping("/vote")
    public Map<String, Object> vote(@PathVariable Long roomId, @RequestBody(required = false) VoteBody body, Authentication auth) {
        return game.vote(roomId, email(auth), body == null ? null : body.target());
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handle(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("message", e.getReason() == null ? "That didn't work" : e.getReason()));
    }

    private static String email(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null || "anonymousUser".equals(auth.getPrincipal())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in again.");
        }
        return auth.getName();
    }

    /* ---------------- live (STOMP) ---------------- */

    public record Move(Double dx, Double dy, Boolean work) {
    }

    public record Act(String a) {
    }

    public record Say(String text) {
    }

    @Controller
    public static class Live {
        private final GaddarService game;

        public Live(GaddarService game) {
            this.game = game;
        }

        @MessageMapping({"/voice-rooms/{roomId}/gaddar", "/gaddar/t/{roomId}"})
        public void move(@DestinationVariable Long roomId, @Payload Move m, Principal who) {
            if (who == null || m == null) return;
            game.input(roomId, who.getName(), m.dx() == null ? 0 : m.dx(), m.dy() == null ? 0 : m.dy(), Boolean.TRUE.equals(m.work()));
        }

        @MessageMapping({"/voice-rooms/{roomId}/gaddar.chat", "/gaddar/t/{roomId}/chat"})
        public void chat(@DestinationVariable Long roomId, @Payload Say say, Principal who) {
            if (who == null || say == null) return;
            game.chat(roomId, who.getName(), say.text());
        }

        @MessageMapping({"/voice-rooms/{roomId}/gaddar.act", "/gaddar/t/{roomId}/act"})
        public void act(@DestinationVariable Long roomId, @Payload Act a, Principal who) {
            if (who == null || a == null) return;
            game.act(roomId, who.getName(), a.a());
        }
    }
}
