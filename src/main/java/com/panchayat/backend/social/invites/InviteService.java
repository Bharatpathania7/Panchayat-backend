package com.panchayat.backend.social.invites;

import com.panchayat.backend.admin.media.MediaUrls;
import com.panchayat.backend.chat.dto.ChatMessageResponse;
import com.panchayat.backend.chat.entity.ChatMessage;
import com.panchayat.backend.chat.service.ChatMessageService;
import com.panchayat.backend.chat.service.ChatSocialService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Invites sent in chats: a room ("come to my room") or a game table ("play
 * Jackaroo with me"). The server writes the invite (room name, code, game,
 * who's playing) so what the card shows is true; it goes as a chat message
 * starting with MARK followed by JSON, which the app shows as a card.
 *
 * Password rooms: the invite carries a room invite code (24 h) that lets the
 * friend in without the password ("inv:CODE" in place of the password).
 * Game tables have a 6-letter code anyone can join with (GET /games/code/{code}).
 */
@Service
public class InviteService {
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private com.panchayat.backend.progress.tasks.DailyTaskService dailyTasks;


    public static final String MARK = "::invite::";
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RNG = new SecureRandom();
    private static final Duration INVITE_TTL = Duration.ofHours(24);

    private final JdbcTemplate jdbc;
    private final ChatMessageService messages;
    private final ChatSocialService social;
    private final ObjectMapper json = new ObjectMapper();

    public InviteService(JdbcTemplate jdbc, ChatMessageService messages, ChatSocialService social) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.social = social;
    }

    public static String code(int n) {
        StringBuilder b = new StringBuilder(n);
        for (int i = 0; i < n; i++) b.append(ALPHABET.charAt(RNG.nextInt(ALPHABET.length())));
        return b.toString();
    }

    /** A room invite code (24 h) for a password room. Only someone in the room can make one. */
    @Transactional
    public String roomInvite(Long roomId, Long me) {
        requireInRoom(roomId, me);
        String c = code(8);
        jdbc.update("insert into room_invites (code, room_id, created_by, expires_at) values (?, ?, ?, ?)",
                c, roomId, me, Timestamp.from(Instant.now().plus(INVITE_TTL)));
        return c;
    }

    /** Whether "inv:CODE" lets someone into this room now. */
    public boolean inviteAccepted(Long roomId, String password) {
        if (password == null || !password.startsWith("inv:")) return false;
        Integer n = jdbc.queryForObject("select count(*) from room_invites where code = ? and room_id = ? and expires_at > now()",
                Integer.class, password.substring(4).trim().toUpperCase(Locale.ROOT), roomId);
        return n != null && n > 0;
    }

    /** Send a room or game invite into a chat (direct or group). */
    @Transactional
    public Map<String, Object> send(Long me, Long conversationId, String kind, Long roomId, Long gameId) {
        dailyTasks.track(me, "INVITE_FRIEND", 1); // counted after the invite is saved (after commit)
        String k = kind == null ? "" : kind.toUpperCase(Locale.ROOT);
        Map<String, Object> card = new LinkedHashMap<>();
        if ("GAME".equals(k)) {
            List<Map<String, Object>> g = jdbc.queryForList("select id, room_id, game, status, code, state from room_games where id = ?", gameId);
            if (g.isEmpty()) throw notFound("That game is gone.");
            Map<String, Object> row = g.get(0);
            if (!"WAITING".equals(row.get("status")) && !"PLAYING".equals(row.get("status"))) throw conflict("That game is over.");
            roomId = ((Number) row.get("room_id")).longValue();
            card.put("kind", "GAME");
            card.put("game", row.get("game"));
            card.put("gameId", String.valueOf(row.get("id")));
            card.put("gameCode", row.get("code"));
            card.put("status", row.get("status"));
            try {
                Map<?, ?> st = json.readValue(String.valueOf(row.get("state")), Map.class);
                card.put("players", st.get("players") instanceof List<?> l ? l.size() : 0);
            } catch (RuntimeException e) {
                card.put("players", 0);
            }
        } else if ("ROOM".equals(k)) {
            card.put("kind", "ROOM");
        } else {
            throw bad("Invite to a ROOM or a GAME.");
        }
        if (roomId == null) throw bad("Choose a room.");
        requireInRoom(roomId, me);
        Map<String, Object> room = jdbc.queryForMap("select id, name, room_code, cover_image_url, password, is_private, status from voice_rooms where id = ?", roomId);
        if (!"ACTIVE".equals(room.get("status"))) throw conflict("That room is closed.");
        card.put("roomId", String.valueOf(roomId));
        card.put("roomName", room.get("name"));
        card.put("roomCode", room.get("room_code"));
        card.put("coverUrl", MediaUrls.link((String) room.get("cover_image_url")));
        boolean locked = room.get("password") != null && !String.valueOf(room.get("password")).isBlank();
        card.put("private", locked || Boolean.TRUE.equals(room.get("is_private")));
        if (locked) card.put("invite", "inv:" + roomInvite(roomId, me));
        card.put("byUserId", String.valueOf(me));
        card.put("expiresAt", Instant.now().plus(INVITE_TTL).toString());

        String text = MARK + json.writeValueAsString(card);
        ChatMessage saved = messages.sendTextMessage(me, conversationId, text);
        ChatMessageResponse response = ChatMessageResponse.from(saved, text);
        social.deliver(conversationId, response);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("message", response);
        out.put("invite", card);
        return out;
    }

    /** A game table by its code: where it is and how to get in. */
    @Transactional
    public Map<String, Object> byCode(String code, Long me) {
        String c = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        List<Map<String, Object>> g = jdbc.queryForList(
                "select g.id, g.room_id, g.game, g.status, g.code, v.name, v.password, v.status as room_status from room_games g join voice_rooms v on v.id = g.room_id where g.code = ?", c);
        if (g.isEmpty()) throw notFound("No game with that code.");
        Map<String, Object> row = g.get(0);
        if (!"WAITING".equals(row.get("status")) && !"PLAYING".equals(row.get("status"))) throw conflict("That game is over.");
        if (!"ACTIVE".equals(row.get("room_status"))) throw conflict("That room is closed.");
        long roomId = ((Number) row.get("room_id")).longValue();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("gameId", String.valueOf(row.get("id")));
        out.put("game", row.get("game"));
        out.put("gameCode", row.get("code"));
        out.put("status", row.get("status"));
        out.put("roomId", String.valueOf(roomId));
        out.put("roomName", row.get("name"));
        // A game code lets you into its room even when the room has a password
        boolean locked = row.get("password") != null && !String.valueOf(row.get("password")).isBlank();
        if (locked) {
            String inv = code(8);
            jdbc.update("insert into room_invites (code, room_id, created_by, expires_at) values (?, ?, ?, ?)",
                    inv, roomId, me, Timestamp.from(Instant.now().plus(Duration.ofHours(2))));
            out.put("invite", "inv:" + inv);
        }
        return out;
    }

    private void requireInRoom(Long roomId, Long me) {
        Integer n = jdbc.queryForObject("select count(*) from voice_room_participants where room_id = ? and user_id = ? and status = 'JOINED'", Integer.class, roomId, me);
        if (n == null || n == 0) throw forbidden("Join the room first to invite people to it.");
    }

    private static ResponseStatusException bad(String m) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, m); }
    private static ResponseStatusException notFound(String m) { return new ResponseStatusException(HttpStatus.NOT_FOUND, m); }
    private static ResponseStatusException conflict(String m) { return new ResponseStatusException(HttpStatus.CONFLICT, m); }
    private static ResponseStatusException forbidden(String m) { return new ResponseStatusException(HttpStatus.FORBIDDEN, m); }
}
