
        package com.panchayat.backend.voice.service;

import com.panchayat.backend.auth.entity.User;
import com.panchayat.backend.auth.repository.UserRepository;
import com.panchayat.backend.chat.encryption.ChatEncryptionService;
import com.panchayat.backend.voice.entity.VoiceRoom;
import com.panchayat.backend.voice.entity.VoiceRoomChatMessage;
import com.panchayat.backend.voice.repository.VoiceRoomChatMessageRepository;
import com.panchayat.backend.voice.repository.VoiceRoomParticipantRepository;
import com.panchayat.backend.voice.repository.VoiceRoomRepository;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

@Service
public class VoiceRoomChatService {

    private static final int MAX_MESSAGE_LENGTH = 2000;
    private static final int MAX_HISTORY_SIZE = 100;

    private final VoiceRoomChatMessageRepository messageRepository;
    private final VoiceRoomRepository roomRepository;
    private final VoiceRoomParticipantRepository participantRepository;
    private final UserRepository userRepository;
    private final ChatEncryptionService encryptionService;

    public VoiceRoomChatService(
            VoiceRoomChatMessageRepository messageRepository,
            VoiceRoomRepository roomRepository,
            VoiceRoomParticipantRepository participantRepository,
            UserRepository userRepository,
            ChatEncryptionService encryptionService
    ) {
        this.messageRepository = messageRepository;
        this.roomRepository = roomRepository;
        this.participantRepository = participantRepository;
        this.userRepository = userRepository;
        this.encryptionService = encryptionService;
    }

    /**
     * Sends a message using the current Spring Security authentication.
     * Useful for authenticated REST-based operations.
     */
    @Transactional
    public VoiceRoomChatMessage sendMessage(
            Long roomId,
            String messageText
    ) {
        User user = getAuthenticatedUser();

        return saveMessage(roomId, messageText, user);
    }

    /**
     * Sends a message using the authenticated STOMP Principal.
     * Do not accept the sender's user ID from the client payload.
     */
    @Transactional
    public VoiceRoomChatMessageResponse sendMessageForPrincipal(
            Long roomId,
            String messageText,
            String authenticatedEmail
    ) {
        if (authenticatedEmail == null || authenticatedEmail.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Authentication required."
            );
        }

        User user = userRepository.findByEmailIgnoreCase(
                authenticatedEmail.trim()
        ).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Authenticated user not found."
        ));

        VoiceRoomChatMessage saved = saveMessage(
                roomId,
                messageText,
                user
        );

        return toResponse(saved, user.getId());
    }

    private VoiceRoomChatMessage saveMessage(
            Long roomId,
            String messageText,
            User user
    ) {
        validateRoomId(roomId);

        if (messageText == null || messageText.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Message cannot be empty."
            );
        }

        String normalizedMessage = messageText.trim();

        if (normalizedMessage.length() > MAX_MESSAGE_LENGTH) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Message cannot exceed 2000 characters."
            );
        }

        VoiceRoom room = roomRepository.findById(roomId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Voice room not found."
                ));

        if (!"ACTIVE".equals(room.getStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Voice room is not active."
            );
        }

        verifyJoinedParticipant(roomId, user.getId());

        ChatEncryptionService.EncryptedPayload encrypted =
                encryptionService.encrypt(normalizedMessage);

        VoiceRoomChatMessage message = new VoiceRoomChatMessage();
        message.setRoomId(roomId);
        message.setSenderUserId(user.getId());
        message.setCiphertext(encrypted.ciphertext());
        message.setNonce(encrypted.nonce());
        message.setEncryptionKeyVersion(encrypted.keyVersion());

        return messageRepository.save(message);
    }

    /**
     * Returns recent decrypted messages in chronological order.
     */
    @Transactional(readOnly = true)
    public List<VoiceRoomChatMessageResponse> getRecentMessages(
            Long roomId,
            int requestedLimit
    ) {
        validateRoomId(roomId);

        if (requestedLimit < 1 || requestedLimit > MAX_HISTORY_SIZE) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "History limit must be between 1 and 100."
            );
        }

        User user = getAuthenticatedUser();

        verifyActiveRoom(roomId);
        verifyJoinedParticipant(roomId, user.getId());

        List<VoiceRoomChatMessage> messages =
                messageRepository.findRecentMessages(
                        roomId,
                        PageRequest.of(0, requestedLimit)
                );

        return messages.stream()
                .sorted(
                        Comparator.comparing(
                                VoiceRoomChatMessage::getCreatedAt
                        ).thenComparing(VoiceRoomChatMessage::getId)
                )
                .map(message -> toResponse(message, user.getId()))
                .toList();
    }

    /**
     * Decrypts one message only for a currently joined participant.
     */
    @Transactional(readOnly = true)
    public VoiceRoomChatMessageResponse decryptMessageForParticipant(
            Long roomId,
            Long messageId
    ) {
        validateRoomId(roomId);

        if (messageId == null || messageId <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid message ID."
            );
        }

        User user = getAuthenticatedUser();

        verifyActiveRoom(roomId);
        verifyJoinedParticipant(roomId, user.getId());

        VoiceRoomChatMessage message = messageRepository.findById(messageId)
                .filter(m -> m.getRoomId().equals(roomId))
                .filter(m -> m.getDeletedAt() == null)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Chat message not found."
                ));

        return toResponse(message, user.getId());
    }

    private VoiceRoomChatMessageResponse toResponse(
            VoiceRoomChatMessage message,
            Long authenticatedUserId
    ) {
        String plaintext = encryptionService.decrypt(
                message.getCiphertext(),
                message.getNonce(),
                message.getEncryptionKeyVersion()
        );

        return new VoiceRoomChatMessageResponse(
                message.getId(),
                message.getRoomId(),
                message.getSenderUserId(),
                plaintext,
                message.getCreatedAt(),
                message.getSenderUserId().equals(authenticatedUserId)
        );
    }

    private void verifyActiveRoom(Long roomId) {
        VoiceRoom room = roomRepository.findById(roomId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Voice room not found."
                ));

        if (!"ACTIVE".equals(room.getStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Voice room not found."
            );
        }
    }

    private void verifyJoinedParticipant(Long roomId, Long userId) {
        boolean isJoined = participantRepository
                .findByRoomIdAndUserIdAndStatus(
                        roomId,
                        userId,
                        "JOINED"
                )
                .isPresent();

        if (!isJoined) {
            throw new AccessDeniedException(
                    "You must be an active participant in this voice room."
            );
        }
    }

    private User getAuthenticatedUser() {
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication.getPrincipal() == null
                || "anonymousUser".equals(authentication.getPrincipal())) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Authentication required."
            );
        }

        return userRepository.findByEmailIgnoreCase(
                authentication.getName()
        ).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Authenticated user not found."
        ));
    }

    private void validateRoomId(Long roomId) {
        if (roomId == null || roomId <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid room ID."
            );
        }
    }

    public record VoiceRoomChatMessageResponse(
            Long id,
            Long roomId,
            Long senderUserId,
            String message,
            Instant createdAt,
            boolean ownMessage
    ) {
    }
}
