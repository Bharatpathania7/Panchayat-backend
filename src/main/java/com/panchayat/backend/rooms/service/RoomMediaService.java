package com.panchayat.backend.rooms.service;

import com.panchayat.backend.voice.entity.VoiceRoom;
import com.panchayat.backend.voice.entity.VoiceRoomType;
import com.panchayat.backend.voice.repository.VoiceRoomParticipantRepository;
import com.panchayat.backend.voice.repository.VoiceRoomRepository;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * What everyone in a room is watching: the room's mode (Normal, YouTube,
 * Kharidari) and the shared YouTube video, playing or paused at a position.
 *
 * The position is stored with the server time it was true at, so every app
 * computes the same "now" position: position + (now - updatedAt) while
 * playing. Changes are broadcast to /topic/voice-rooms/{id}/media.
 *
 * Who can do what (same as the app):
 * - Mode: the owner and admins.
 * - Pick a video, play, pause, seek: the owner, admins and anyone on a seat.
 * - Remove the video: the owner and admins.
 */
@Service
public class RoomMediaService {

    private static final Pattern VIDEO_ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");

    private final VoiceRoomRepository roomRepository;
    private final VoiceRoomParticipantRepository participants;
    private final RoomStateService roomState;
    private final SimpMessagingTemplate messaging;

    /** roomId -> current video (null = none). Guarded by synchronizing on the map entry's room lock. */
    private final Map<Long, Video> videos = new ConcurrentHashMap<>();
    private final Map<Long, Object> locks = new ConcurrentHashMap<>();
    private final Map<Long, Long> versions = new ConcurrentHashMap<>();

    public RoomMediaService(VoiceRoomRepository roomRepository, VoiceRoomParticipantRepository participants,
                            RoomStateService roomState, SimpMessagingTemplate messaging) {
        this.roomRepository = roomRepository;
        this.participants = participants;
        this.roomState = roomState;
        this.messaging = messaging;
    }

    /** position: seconds into the video at updatedAt (epoch ms, server clock). */
    public record Video(String id, String title, boolean playing, double position, long updatedAt, String byUserId) {
        double positionAt(long nowMs) {
            return playing ? position + Math.max(0, nowMs - updatedAt) / 1000.0 : position;
        }
    }

    public Map<String, Object> get(Long roomId) {
        VoiceRoom room = activeRoom(roomId);
        synchronized (lock(roomId)) {
            return snapshot(room, null, null);
        }
    }

    public Map<String, Object> setMode(Long roomId, Long actor, String mode) {
        VoiceRoom room = activeRoom(roomId);
        requireInRoom(roomId, actor);
        if (!roomState.isModerator(room, actor)) throw forbidden("Only the owner or an admin can change the mode");
        VoiceRoomType type;
        try {
            type = VoiceRoomType.valueOf(mode == null ? "" : mode.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw bad("Mode must be VOICE, WATCH or KHARIDARI");
        }
        synchronized (lock(roomId)) {
            if (room.getRoomType() != type) {
                room.setRoomType(type);
                roomRepository.save(room);
            }
            return publish(room, "MODE_CHANGED", actor);
        }
    }

    public Map<String, Object> setVideo(Long roomId, Long actor, String videoId, String title) {
        VoiceRoom room = activeRoom(roomId);
        requireController(room, actor);
        if (videoId == null || !VIDEO_ID.matcher(videoId).matches()) throw bad("That isn't a YouTube video id");
        String t = title == null || title.isBlank() ? "YouTube video" : title.trim();
        if (t.length() > 200) t = t.substring(0, 200);
        synchronized (lock(roomId)) {
            videos.put(roomId, new Video(videoId, t, true, 0, System.currentTimeMillis(), String.valueOf(actor)));
            return publish(room, "VIDEO_CHANGED", actor);
        }
    }

    public Map<String, Object> removeVideo(Long roomId, Long actor) {
        VoiceRoom room = activeRoom(roomId);
        requireInRoom(roomId, actor);
        if (!roomState.isModerator(room, actor)) throw forbidden("Only the owner or an admin can remove the video");
        synchronized (lock(roomId)) {
            videos.remove(roomId);
            return publish(room, "VIDEO_REMOVED", actor);
        }
    }

    /** action: play, pause or seek. position: seconds (where the person's player is). */
    public Map<String, Object> control(Long roomId, Long actor, String action, Double position) {
        VoiceRoom room = activeRoom(roomId);
        requireController(room, actor);
        synchronized (lock(roomId)) {
            Video v = videos.get(roomId);
            if (v == null) throw conflict("No video is playing");
            long now = System.currentTimeMillis();
            double at = position == null || position.isNaN() || position < 0 ? v.positionAt(now) : Math.min(position, 24 * 3600);
            boolean playing = switch (action == null ? "" : action) {
                case "play" -> true;
                case "pause" -> false;
                case "seek" -> v.playing();
                default -> throw bad("action must be play, pause or seek");
            };
            videos.put(roomId, new Video(v.id(), v.title(), playing, at, now, String.valueOf(actor)));
            return publish(room, action.toUpperCase(), actor);
        }
    }

    public void onRoomClosed(Long roomId) {
        videos.remove(roomId);
        versions.remove(roomId);
        locks.remove(roomId);
    }

    // ---- internals ----

    private Map<String, Object> publish(VoiceRoom room, String event, Long actor) {
        versions.merge(room.getId(), 1L, Long::sum);
        Map<String, Object> state = snapshot(room, event, actor);
        Object payload = state;
        messaging.convertAndSend("/topic/voice-rooms/" + room.getId() + "/media", payload);
        return state;
    }

    private Map<String, Object> snapshot(VoiceRoom room, String event, Long actor) {
        long now = System.currentTimeMillis();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("roomId", String.valueOf(room.getId()));
        out.put("version", versions.getOrDefault(room.getId(), 0L));
        out.put("mode", room.getRoomType().name());
        Video v = videos.get(room.getId());
        if (v == null) {
            out.put("video", null);
        } else {
            Map<String, Object> video = new LinkedHashMap<>();
            video.put("id", v.id());
            video.put("title", v.title());
            video.put("playing", v.playing());
            video.put("position", v.position());
            video.put("updatedAt", v.updatedAt());
            video.put("byUserId", v.byUserId());
            out.put("video", video);
        }
        // Lets each app line its clock up with the server's
        out.put("serverTime", now);
        if (event != null) {
            out.put("event", Map.of("type", event, "actorId", actor == null ? "" : String.valueOf(actor)));
        }
        return out;
    }

    private void requireController(VoiceRoom room, Long actor) {
        requireInRoom(room.getId(), actor);
        if (!roomState.isModerator(room, actor) && !roomState.isSeated(room.getId(), actor)) {
            throw forbidden("Take a seat to control the video. The owner and admins can always change it.");
        }
    }

    private void requireInRoom(Long roomId, Long actor) {
        if (participants.findByRoomIdAndUserIdAndStatus(roomId, actor, "JOINED").isEmpty()) {
            throw forbidden("Join the room first");
        }
    }

    private Object lock(Long roomId) {
        return locks.computeIfAbsent(roomId, k -> new Object());
    }

    private VoiceRoom activeRoom(Long roomId) {
        if (roomId == null || roomId <= 0) throw bad("Invalid room ID");
        VoiceRoom room = roomRepository.findById(roomId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Voice room not found"));
        if (!"ACTIVE".equals(room.getStatus())) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Voice room is not available");
        return room;
    }

    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }

    private static ResponseStatusException forbidden(String m) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, m);
    }

    private static ResponseStatusException conflict(String m) {
        return new ResponseStatusException(HttpStatus.CONFLICT, m);
    }
}
