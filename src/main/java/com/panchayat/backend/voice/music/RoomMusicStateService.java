package com.panchayat.backend.voice.music;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps the last "now playing" broadcast per room, in memory only (never persisted — it's exactly
 * as stale as the room's live state). Lets someone who just joined see it immediately on
 * GET /api/voice-rooms/{roomId}/music/now-playing instead of waiting for the next change.
 */
@Service
public class RoomMusicStateService {

    private final Map<Long, NowPlaying> state = new ConcurrentHashMap<>();

    public record NowPlaying(String title, String artist, boolean playing) {}

    public void set(Long roomId, NowPlaying playing) {
        if (playing == null || playing.title() == null || playing.title().isBlank()) state.remove(roomId);
        else state.put(roomId, playing);
    }

    public NowPlaying get(Long roomId) {
        return state.get(roomId);
    }
}
