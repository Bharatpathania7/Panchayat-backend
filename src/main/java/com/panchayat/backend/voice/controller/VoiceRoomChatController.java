package com.panchayat.backend.voice.controller;

import com.panchayat.backend.voice.service.VoiceRoomChatService;
import com.panchayat.backend.voice.service.VoiceRoomChatService.VoiceRoomChatMessageResponse;

import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Room chat over plain HTTP:
 *   GET  /api/voice-rooms/{roomId}/chat           recent messages
 *   POST /api/voice-rooms/{roomId}/chat {message} send one (the same as the
 *        live /app/voice-rooms/{roomId}/chat, for when the app's live
 *        connection is reconnecting). Everyone in the room gets it on
 *        /topic/voice-rooms/{roomId}/chat either way.
 */
@RestController
@RequestMapping("/api/voice-rooms/{roomId}/chat")
public class VoiceRoomChatController {

    private final VoiceRoomChatService chatService;
    private final SimpMessagingTemplate messagingTemplate;

    public VoiceRoomChatController(VoiceRoomChatService chatService, SimpMessagingTemplate messagingTemplate) {
        this.chatService = chatService;
        this.messagingTemplate = messagingTemplate;
    }

    public record SendRequest(String message) {
    }

    @GetMapping
    public List<VoiceRoomChatMessageResponse> getChatHistory(
            @PathVariable Long roomId,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return chatService.getRecentMessages(roomId, limit);
    }

    @PostMapping
    public VoiceRoomChatMessageResponse send(@PathVariable Long roomId, @RequestBody SendRequest body, Authentication auth) {
        if (auth == null || auth.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in first");
        }
        if (body == null || body.message() == null || body.message().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Type a message first");
        }
        VoiceRoomChatMessageResponse response = chatService.sendMessageForPrincipal(roomId, body.message(), auth.getName());
        Object payload = response;
        messagingTemplate.convertAndSend("/topic/voice-rooms/" + roomId + "/chat", payload);
        return response;
    }
}
