package com.panchayat.backend.music;

import com.panchayat.backend.rooms.service.RoomStateService;
import com.panchayat.backend.voice.entity.VoiceRoom;
import com.panchayat.backend.voice.repository.VoiceRoomParticipantRepository;
import com.panchayat.backend.voice.repository.VoiceRoomRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Songs the people in a room ask for. Anyone in the room can add a song to the list; only the owner and the admins
 * can put one in the queue (and play it) or dismiss it. The list lives in memory for as long as the room is open.
 * Live updates: /topic/voice-rooms/{id}/songs.
 */
@Service
public class RoomSongRequestService {

    static final int MAX_PER_ROOM = 60;
    static final int MAX_PER_PERSON = 5;

    private final VoiceRoomRepository rooms;
    private final VoiceRoomParticipantRepository participants;
    private final RoomStateService roomState;
    private final SimpMessagingTemplate messaging;
    private final JdbcTemplate jdbc;

    private final Map<Long, List<Req>> lists = new ConcurrentHashMap<>();
    private final Map<Long, Long> versions = new ConcurrentHashMap<>();

    public RoomSongRequestService(VoiceRoomRepository rooms, VoiceRoomParticipantRepository participants, RoomStateService roomState,
                                  SimpMessagingTemplate messaging, JdbcTemplate jdbc) {
        this.rooms = rooms;
        this.participants = participants;
        this.roomState = roomState;
        this.messaging = messaging;
        this.jdbc = jdbc;
    }

    private record Req(String id, Long userId, String userName, Map<String, Object> song, long at) {
    }

    public Map<String, Object> list(Long roomId) {
        activeRoom(roomId);
        return snapshot(roomId, null);
    }

    public Map<String, Object> add(Long roomId, Long actor, Map<String, Object> song) {
        activeRoom(roomId);
        if (participants.findByRoomIdAndUserIdAndStatus(roomId, actor, "JOINED").isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Join the room first");
        }
        Map<String, Object> clean = clean(song);
        List<Req> list = lists.computeIfAbsent(roomId, k -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (list) {
            if (list.size() >= MAX_PER_ROOM) throw new ResponseStatusException(HttpStatus.CONFLICT, "The song list is full. Wait for the owner to play some.");
            if (list.stream().anyMatch(r -> r.song().get("id").equals(clean.get("id")))) throw new ResponseStatusException(HttpStatus.CONFLICT, "That song is already on the list.");
            if (list.stream().filter(r -> r.userId().equals(actor)).count() >= MAX_PER_PERSON) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "You can have " + MAX_PER_PERSON + " songs on the list at a time.");
            }
            String name = jdbc.query("select full_name from users where id = ?", rs -> rs.next() ? rs.getString(1) : null, actor);
            list.add(new Req(UUID.randomUUID().toString().substring(0, 8), actor, name == null || name.isBlank() ? "Someone" : name.strip(), clean, System.currentTimeMillis()));
        }
        return publish(roomId, "ADDED", actor);
    }

    /** The owner or an admin takes a request off (after ordering it or to say no); the person who asked can take theirs back. */
    public Map<String, Object> remove(Long roomId, Long actor, String id) {
        VoiceRoom room = activeRoom(roomId);
        List<Req> list = lists.get(roomId);
        if (list == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That request isn't on the list.");
        synchronized (list) {
            Req r = list.stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "That request isn't on the list."));
            if (!r.userId().equals(actor) && !roomState.isModerator(room, actor)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the owner and the admins can do that.");
            }
            list.remove(r);
        }
        return publish(roomId, "REMOVED", actor);
    }

    private Map<String, Object> publish(Long roomId, String event, Long actor) {
        versions.merge(roomId, 1L, Long::sum);
        Map<String, Object> state = snapshot(roomId, Map.of("type", event, "actorId", String.valueOf(actor)));
        messaging.convertAndSend("/topic/voice-rooms/" + roomId + "/songs", (Object) state);
        return state;
    }

    private Map<String, Object> snapshot(Long roomId, Map<String, Object> event) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("roomId", String.valueOf(roomId));
        out.put("version", versions.getOrDefault(roomId, 0L));
        List<Map<String, Object>> rows = new ArrayList<>();
        List<Req> list = lists.get(roomId);
        if (list != null) {
            synchronized (list) {
                for (Req r : list) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", r.id());
                    m.put("userId", String.valueOf(r.userId()));
                    m.put("userName", r.userName());
                    m.put("song", r.song());
                    m.put("at", r.at());
                    rows.add(m);
                }
            }
        }
        out.put("requests", rows);
        if (event != null) out.put("event", event);
        return out;
    }

    /** Only the fields a song needs, with sane lengths; a link must be https (or one of our own paths). */
    static Map<String, Object> clean(Map<String, Object> in) {
        if (in == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a song.");
        String id = str(in.get("id"), 120);
        String title = str(in.get("title"), 120);
        if (id == null || title == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a song.");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("title", title);
        String artist = str(in.get("artist"), 120);
        out.put("artist", artist == null ? "" : artist);
        String uploader = str(in.get("uploader"), 80);
        out.put("uploader", uploader == null ? "" : uploader);
        String uploaderName = str(in.get("uploaderName"), 80);
        if (uploaderName != null) out.put("uploaderName", uploaderName);
        Object d = in.get("duration");
        out.put("duration", d instanceof Number n ? Math.max(0, Math.min(36_000, n.intValue())) : 0);
        String src = str(in.get("src"), 600);
        if (src != null) {
            if (!(src.startsWith("https://") || (src.startsWith("/") && !src.startsWith("//")))) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That song's link isn't allowed.");
            out.put("src", src);
        }
        String source = str(in.get("source"), 20);
        if (source != null && Set.of("built-in", "upload", "audius", "jamendo").contains(source)) out.put("source", source);
        return out;
    }

    private static String str(Object o, int max) {
        if (!(o instanceof String s)) return null;
        String t = s.strip();
        if (t.isEmpty()) return null;
        return t.length() > max ? t.substring(0, max) : t;
    }

    private VoiceRoom activeRoom(Long roomId) {
        if (roomId == null || roomId <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid room ID");
        VoiceRoom room = rooms.findById(roomId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Voice room not found"));
        if (!"ACTIVE".equals(room.getStatus())) {
            lists.remove(roomId);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Voice room is not available");
        }
        return room;
    }
}
