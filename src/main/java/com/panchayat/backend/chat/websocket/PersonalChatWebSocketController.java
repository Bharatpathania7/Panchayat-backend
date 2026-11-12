
        package com.panchayat.backend.chat.websocket;

import com.panchayat.backend.auth.entity.User;
import com.panchayat.backend.auth.repository.UserRepository;
import com.panchayat.backend.chat.dto.ChatMessageResponse;
import com.panchayat.backend.chat.entity.ChatConversationParticipant;
import com.panchayat.backend.chat.entity.ChatMessage;
import com.panchayat.backend.chat.repository.ChatConversationParticipantRepository;
import com.panchayat.backend.chat.service.ChatMessageService;

import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;

import java.util.List;

@Controller
public class PersonalChatWebSocketController {

    private final ChatMessageService chatMessageService;
    private final UserRepository userRepository;
    private final ChatConversationParticipantRepository participantRepository;
    private final SimpMessagingTemplate messagingTemplate;

    public PersonalChatWebSocketController(
            ChatMessageService chatMessageService,
            UserRepository userRepository,
            ChatConversationParticipantRepository participantRepository,
            SimpMessagingTemplate messagingTemplate
    ) {
        this.chatMessageService = chatMessageService;
        this.userRepository = userRepository;
        this.participantRepository = participantRepository;
        this.messagingTemplate = messagingTemplate;
    }

    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private com.panchayat.backend.push.PushService push;

    @MessageMapping("/chat.send")
    public void sendMessage(
            @Payload SendChatMessageRequest request,
            StompHeaderAccessor accessor
    ) {
        if (accessor.getUser() == null
                || accessor.getUser().getName() == null
                || accessor.getUser().getName().isBlank()) {
            throw new AccessDeniedException(
                    "Authentication is required."
            );
        }

        if (request == null
                || request.conversationId() == null
                || request.conversationId() <= 0
                || request.message() == null
                || request.message().isBlank()) {
            throw new IllegalArgumentException(
                    "A valid conversation ID and message are required."
            );
        }

        String senderEmail = accessor.getUser().getName();

        User sender = userRepository
                .findByEmailIgnoreCase(senderEmail)
                .orElseThrow(() -> new AccessDeniedException(
                        "Authenticated user was not found."
                ));

        Long senderId = sender.getId();
        Long conversationId = request.conversationId();

        // Existing service verifies conversation membership,
        // encrypts the text, and persists the message.
        ChatMessage savedMessage = chatMessageService.sendTextMessage(
                senderId,
                conversationId,
                request.message()
        );

        String decryptedText =
                chatMessageService.decryptMessageForParticipant(
                        senderId,
                        conversationId,
                        savedMessage.getId()
                );

        ChatMessageResponse response = ChatMessageResponse.from(
                savedMessage,
                decryptedText
        );

        // Fetch recipients from the actual conversation membership.
        List<ChatConversationParticipant> participants =
                participantRepository.findByConversationId(conversationId);

        for (ChatConversationParticipant participant : participants) {
            Long recipientId = participant.getUserId();

            // Don't deliver a duplicate message to the sender here.
            if (recipientId == null || recipientId.equals(senderId)) {
                continue;
            }

            User recipient = userRepository.findById(recipientId)
                    .orElse(null);

            if (recipient == null
                    || recipient.getEmail() == null
                    || recipient.getEmail().isBlank()) {
                continue;
            }

            // Deliver only to the other participant's user destination.
            messagingTemplate.convertAndSendToUser(
                    recipient.getEmail(),
                    "/queue/chat",
                    response
            );
            // Not online in the app: a notification on their phone (the message text itself is not sent through the push service)
            push.toEmail(recipient.getEmail(), sender.getFullName() == null || sender.getFullName().isBlank() ? "New message" : sender.getFullName(),
                    "Sent you a message", java.util.Map.of("type", "CHAT", "conversationId", String.valueOf(conversationId)));
        }

        // Send a confirmation to the sender's private queue.
        messagingTemplate.convertAndSendToUser(
                senderEmail,
                "/queue/chat",
                response
        );
    }

    public record SendChatMessageRequest(
            Long conversationId,
            String message
    ) {
    }
}
