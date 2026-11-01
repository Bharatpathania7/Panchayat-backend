
        package com.panchayat.backend.voice.controller;

import com.panchayat.backend.voice.service.VoiceRoomChatService;
import com.panchayat.backend.voice.service.VoiceRoomChatService.VoiceRoomChatMessageResponse;

import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.security.Principal;

@Controller
public class VoiceRoomChatWebSocketController {

    private final VoiceRoomChatService chatService;
    private final SimpMessagingTemplate messagingTemplate;

    public VoiceRoomChatWebSocketController(
            VoiceRoomChatService chatService,
            SimpMessagingTemplate messagingTemplate
    ) {
        this.chatService = chatService;
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Client sends:
     * /app/voice-rooms/{roomId}/chat
     *
     * Server broadcasts:
     * /topic/voice-rooms/{roomId}/chat
     */
    @MessageMapping("/voice-rooms/{roomId}/chat")
    public void sendMessage(
            @DestinationVariable Long roomId,
            @Payload SendVoiceRoomChatRequest request,
            Principal principal
    ) {
        if (principal == null
                || principal.getName() == null
                || principal.getName().isBlank()) {
            throw new IllegalArgumentException(
                    "Authenticated WebSocket user is required."
            );
        }

        if (request == null || request.message() == null) {
            throw new IllegalArgumentException(
                    "Message payload is required."
            );
        }

        VoiceRoomChatMessageResponse response =
                chatService.sendMessageForPrincipal(
                        roomId,
                        request.message(),
                        principal.getName()
                );

        messagingTemplate.convertAndSend(
                "/topic/voice-rooms/" + roomId + "/chat",
                response
        );
    }

    public record SendVoiceRoomChatRequest(String message) {
    }
}
