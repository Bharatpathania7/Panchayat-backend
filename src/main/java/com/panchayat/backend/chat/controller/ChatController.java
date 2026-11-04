
        package com.panchayat.backend.chat.controller;

import com.panchayat.backend.auth.entity.User;
import com.panchayat.backend.auth.repository.UserRepository;
import com.panchayat.backend.chat.dto.ChatMessageResponse;
import com.panchayat.backend.chat.dto.CreateConversationRequest;
import com.panchayat.backend.chat.dto.SendChatMessageRequest;
import com.panchayat.backend.chat.entity.ChatConversation;
import com.panchayat.backend.chat.entity.ChatMessage;
import com.panchayat.backend.chat.entity.ChatMessageType;
import com.panchayat.backend.chat.service.ChatConversationService;
import com.panchayat.backend.chat.service.ChatImageValidator;
import com.panchayat.backend.chat.service.ChatMessageService;

import jakarta.validation.Valid;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {
    @org.springframework.beans.factory.annotation.Autowired
    private com.panchayat.backend.sarpanch.SarpanchService sarpanch;


    @org.springframework.beans.factory.annotation.Autowired
    private com.panchayat.backend.chat.service.ChatSocialService chatSocial;


    private static final long MAX_IMAGE_SIZE = 5L * 1024 * 1024;

    private final UserRepository userRepository;
    private final ChatConversationService conversationService;
    private final ChatMessageService messageService;
    private final ChatImageValidator chatImageValidator;

    public ChatController(
            UserRepository userRepository,
            ChatConversationService conversationService,
            ChatMessageService messageService,
            ChatImageValidator chatImageValidator
    ) {
        this.userRepository = userRepository;
        this.conversationService = conversationService;
        this.messageService = messageService;
        this.chatImageValidator = chatImageValidator;
    }

    @PostMapping("/conversations")
    public ConversationResponse createConversation(
            Authentication authentication,
            @Valid @RequestBody CreateConversationRequest request
    ) {
        Long authenticatedUserId =
                getAuthenticatedUserId(authentication);

        ChatConversation conversation =
                conversationService.getOrCreateConversation(
                        authenticatedUserId,
                        request.recipientUserId()
                );

        return new ConversationResponse(
                conversation.getId(),
                conversation.getCreatedAt(),
                conversation.getUpdatedAt()
        );
    }

    @GetMapping("/conversations")
    public List<ChatConversationService.ConversationListItem> getConversations(
            Authentication authentication
    ) {
        Long authenticatedUserId =
                getAuthenticatedUserId(authentication);

        return conversationService.getConversationList(
                authenticatedUserId
        );
    }

    @PostMapping("/conversations/{conversationId}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public ChatMessageResponse sendMessage(
            Authentication authentication,
            @PathVariable Long conversationId,
            @Valid @RequestBody SendChatMessageRequest request
    ) {
        Long authenticatedUserId =
                getAuthenticatedUserId(authentication);

        ChatMessage savedMessage =
                messageService.sendTextMessage(
                        authenticatedUserId,
                        conversationId,
                        request.message()
                );

        ChatMessageResponse response = ChatMessageResponse.from(
                savedMessage,
                request.message()
        );
        // Live to everyone in the chat (people in a group, or the other person)
        chatSocial.deliver(conversationId, response);
        return response;
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public Page<ChatMessageResponse> getMessages(
            Authentication authentication,
            @PathVariable Long conversationId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size
    ) {
        Long authenticatedUserId =
                getAuthenticatedUserId(authentication);

        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid pagination parameters."
            );
        }

        Page<ChatMessage> messages =
                messageService.getConversationMessages(
                        authenticatedUserId,
                        conversationId,
                        PageRequest.of(
                                page,
                                size,
                                Sort.by(
                                        Sort.Direction.DESC,
                                        "createdAt",
                                        "id"
                                )
                        )
                );

        java.util.Map<Long, java.util.Map<String, Object>> extras = sarpanch.extras(
                messages.getContent().stream().filter(m -> m.getMessageType() == ChatMessageType.SYSTEM).map(ChatMessage::getId).toList());
        return messages.map(message -> {
            String text = null;

            if (message.getMessageType() == ChatMessageType.TEXT || message.getMessageType() == ChatMessageType.SYSTEM) {
                text = messageService.decryptMessageForParticipant(
                        authenticatedUserId,
                        conversationId,
                        message.getId()
                );
            }

            ChatMessageResponse r = ChatMessageResponse.from(message, text);
            return message.getMessageType() == ChatMessageType.SYSTEM ? r.withSystem(extras.get(message.getId())) : r;
        });
    }

    @PostMapping(
            value = "/conversations/{conversationId}/messages/image",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<Map<String, Object>> uploadImage(
            Authentication authentication,
            @PathVariable Long conversationId,
            @RequestParam("file") MultipartFile file
    ) {
        Long authenticatedUserId =
                getAuthenticatedUserId(authentication);

        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Please select an image."
            );
        }

        if (file.getSize() > MAX_IMAGE_SIZE) {
            throw new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "Image size must not exceed 5 MB."
            );
        }

        final byte[] imageBytes;

        try {
            imageBytes = file.getBytes();
        } catch (IOException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Unable to read uploaded image."
            );
        }

        final String validatedContentType;

        try {
            validatedContentType =
                    chatImageValidator.validateAndDetectContentType(
                            imageBytes
                    );
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    ex.getMessage()
            );
        }

        ChatMessage message =
                messageService.sendImageMessage(
                        authenticatedUserId,
                        conversationId,
                        imageBytes,
                        validatedContentType
                );

        chatSocial.deliver(conversationId, ChatMessageResponse.from(message, null));

        String imageUrl =
                "/api/v1/chat/conversations/"
                        + conversationId
                        + "/messages/"
                        + message.getId()
                        + "/image";

        return ResponseEntity.status(HttpStatus.CREATED).body(
                Map.of(
                        "messageId", message.getId(),
                        "conversationId", message.getConversationId(),
                        "messageType", message.getMessageType().name(),
                        "createdAt", message.getCreatedAt(),
                        "imageUrl", imageUrl
                )
        );
    }

    @GetMapping(
            "/conversations/{conversationId}/messages/{messageId}/image"
    )
    public ResponseEntity<byte[]> downloadImage(
            Authentication authentication,
            @PathVariable Long conversationId,
            @PathVariable Long messageId
    ) {
        Long authenticatedUserId =
                getAuthenticatedUserId(authentication);

        ChatMessageService.DownloadedImage image =
                messageService.getImageForParticipant(
                        authenticatedUserId,
                        conversationId,
                        messageId
                );

        MediaType mediaType;

        try {
            mediaType =
                    MediaType.parseMediaType(image.contentType());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Stored image content type is invalid."
            );
        }

        return ResponseEntity.ok()
                .contentType(mediaType)
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Disposition", "inline")
                .body(image.imageBytes());
    }

    /**
     * Marks unread messages in a conversation as read.
     */
    @PostMapping("/conversations/{conversationId}/read")
    public ResponseEntity<Map<String, Object>> markConversationAsRead(
            Authentication authentication,
            @PathVariable Long conversationId
    ) {
        Long authenticatedUserId =
                getAuthenticatedUserId(authentication);

        int updatedCount =
                messageService.markConversationAsRead(
                        authenticatedUserId,
                        conversationId
                );

        return ResponseEntity.ok(
                Map.of(
                        "conversationId", conversationId,
                        "markedAsRead", true,
                        "updatedCount", updatedCount
                )
        );
    }

    /**
     * Returns the authenticated user's total unread message count.
     */
    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Object>> getUnreadCount(
            Authentication authentication
    ) {
        Long authenticatedUserId =
                getAuthenticatedUserId(authentication);

        long unreadCount =
                messageService.getUnreadCount(authenticatedUserId);

        return ResponseEntity.ok(
                Map.of("unreadCount", unreadCount)
        );
    }

    private Long getAuthenticatedUserId(
            Authentication authentication
    ) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication.getPrincipal() == null) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Authentication is required."
            );
        }

        String email = authentication.getName();

        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED,
                        "Authenticated user was not found."
                ));

        return user.getId();
    }

    public record ConversationResponse(
            Long id,
            Instant createdAt,
            Instant updatedAt
    ) {
    }
}
