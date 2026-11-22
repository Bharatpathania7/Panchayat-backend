package com.panchayat.backend.voice.music;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/voice-rooms/{roomId}/music/now-playing — what's playing right now, for someone who just
 * joined the room (the live /topic only reaches people already connected when it's sent).
 */
@RestController
@RequestMapping("/api/voice-rooms/{roomId}/music")
public class RoomMusicController {

    private final RoomMusicStateService state;

    public RoomMusicController(RoomMusicStateService state) {
        this.state = state;
    }

    @GetMapping("/now-playing")
    public RoomMusicStateService.NowPlaying nowPlaying(@PathVariable Long roomId) {
        return state.get(roomId);
    }
}
