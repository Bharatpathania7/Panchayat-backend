package com.panchayat.backend.voice.music;

import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

/**
 * Broadcasts a room's "now playing" so everyone sees the song's name, not just whoever added or
 * controls it. The audio itself already reaches everyone through the room's live call — this only
 * syncs the display (see components/rooms/MusicPill on the frontend).
 *
 * Client sends: /app/voice-rooms/{roomId}/music
 * Server broadcasts: /topic/voice-rooms/{roomId}/music
 */
@Controller
public class RoomMusicWebSocketController {

    private final SimpMessagingTemplate messagingTemplate;
    private final RoomMusicStateService state;

    public RoomMusicWebSocketController(SimpMessagingTemplate messagingTemplate, RoomMusicStateService state) {
        this.messagingTemplate = messagingTemplate;
        this.state = state;
    }

    @MessageMapping("/voice-rooms/{roomId}/music")
    public void update(@DestinationVariable Long roomId, @Payload RoomMusicStateService.NowPlaying body) {
        state.set(roomId, body);
        messagingTemplate.convertAndSend("/topic/voice-rooms/" + roomId + "/music", body);
    }
}
