package com.panchayat.backend.chat.websocket;

import com.panchayat.backend.auth.repository.UserRepository;
import com.panchayat.backend.chat.service.ChatSocialService;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Controller;

/** "X is typing": the app sends /app/chat.typing { conversationId, typing }. */
@Controller
public class ChatTypingWebSocketController {

    private final ChatSocialService social;
    private final UserRepository users;

    public ChatTypingWebSocketController(ChatSocialService social, UserRepository users) {
        this.social = social;
        this.users = users;
    }

    public record Typing(Long conversationId, Boolean typing) {
    }

    @MessageMapping("/chat.typing")
    public void typing(@Payload Typing body, StompHeaderAccessor accessor) {
        if (accessor.getUser() == null || body == null || body.conversationId() == null) return;
        users.findByEmailIgnoreCase(accessor.getUser().getName())
                .ifPresent(u -> social.typing(body.conversationId(), u, !Boolean.FALSE.equals(body.typing())));
    }
}
