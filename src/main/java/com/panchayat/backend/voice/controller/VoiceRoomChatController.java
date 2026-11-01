package com.panchayat.backend.voice.controller;

import com.panchayat.backend.voice.service.VoiceRoomChatService;
import com.panchayat.backend.voice.service.VoiceRoomChatService.VoiceRoomChatMessageResponse;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/voice-rooms/{roomId}/chat")
public class VoiceRoomChatController {

    private final VoiceRoomChatService chatService;

    public VoiceRoomChatController(VoiceRoomChatService chatService) {
        this.chatService = chatService;
    }

    @GetMapping
    public List<VoiceRoomChatMessageResponse> getChatHistory(
            @PathVariable Long roomId,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return chatService.getRecentMessages(roomId, limit);
    }
}