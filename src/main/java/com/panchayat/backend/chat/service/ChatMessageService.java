
        package com.panchayat.backend.chat.service;

import com.panchayat.backend.chat.encryption.ChatEncryptionService;
import com.panchayat.backend.chat.entity.ChatConversationParticipant;
import com.panchayat.backend.chat.entity.ChatMessage;
import com.panchayat.backend.chat.entity.ChatMessageReceipt;
import com.panchayat.backend.chat.entity.ChatMessageType;
import com.panchayat.backend.chat.repository.ChatConversationParticipantRepository;
import com.panchayat.backend.chat.repository.ChatMessageReceiptRepository;
import com.panchayat.backend.chat.repository.ChatMessageRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.panchayat.backend.chat.entity.ChatMessageReceipt;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.List;

@Service
public class ChatMessageService {
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private com.panchayat.backend.progress.tasks.DailyTaskService dailyTasks;


    /** Blocks, groups (field-injected: no change to the constructor). */
    @org.springframework.beans.factory.annotation.Autowired
    private ChatSocialService chatSocial;


    private final ChatConversationParticipantRepository participantRepository;
    private final ChatMessageRepository messageRepository;
    private final ChatMessageReceiptRepository receiptRepository;
    private final ChatEncryptionService encryptionService;
    private final ChatMediaStorageService mediaStorageService;

    public ChatMessageService(
            ChatConversationParticipantRepository participantRepository,
            ChatMessageRepository messageRepository,
            ChatMessageReceiptRepository receiptRepository,
            ChatEncryptionService encryptionService,
            ChatMediaStorageService mediaStorageService
    ) {
        this.participantRepository = participantRepository;
        this.messageRepository = messageRepository;
        this.receiptRepository = receiptRepository;
        this.encryptionService = encryptionService;
        this.mediaStorageService = mediaStorageService;
    }

    /**
     * Sends an encrypted text message and creates receipts
     * for the other conversation participants.
     */
    @Transactional
    public ChatMessage sendTextMessage(
            Long authenticatedUserId,
            Long conversationId,
            String messageText
    ) {
        dailyTasks.track(authenticatedUserId, "SEND_MESSAGE", 1);
        validateIdentifiers(authenticatedUserId, conversationId);

        if (messageText == null || messageText.isBlank()) {
            throw new IllegalArgumentException(
                    "Message cannot be empty."
            );
        }

        if (messageText.length() > 5000) {
            throw new IllegalArgumentException(
                    "Message cannot exceed 5000 characters."
            );
        }

        verifyParticipant(conversationId, authenticatedUserId);
        // Blocked either way: no direct messages
        chatSocial.checkCanSend(conversationId, authenticatedUserId);

        ChatEncryptionService.EncryptedPayload encrypted =
                encryptionService.encrypt(messageText);

        ChatMessage message = new ChatMessage();
        message.setConversationId(conversationId);
        message.setSenderUserId(authenticatedUserId);
        message.setMessageType(ChatMessageType.TEXT);
        message.setCiphertext(encrypted.ciphertext());
        message.setNonce(encrypted.nonce());
        message.setEncryptionKeyVersion(encrypted.keyVersion());

        ChatMessage savedMessage = messageRepository.save(message);

        createRecipientReceipts(savedMessage, authenticatedUserId);

        return savedMessage;
    }

    /**
     * Stores an encrypted image message and creates receipts
     * for the other conversation participants.
     */
    @Transactional
    public ChatMessage sendImageMessage(
            Long authenticatedUserId,
            Long conversationId,
            byte[] imageBytes,
            String contentType
    ) {
        validateIdentifiers(authenticatedUserId, conversationId);

        verifyParticipant(conversationId, authenticatedUserId);
        // Blocked either way: no direct messages
        chatSocial.checkCanSend(conversationId, authenticatedUserId);

        if (imageBytes == null || imageBytes.length == 0) {
            throw new IllegalArgumentException(
                    "Image cannot be empty."
            );
        }

        if (contentType == null ||
                !(contentType.equalsIgnoreCase("image/jpeg")
                        || contentType.equalsIgnoreCase("image/png"))) {
            throw new IllegalArgumentException(
                    "Only JPEG and PNG images are supported."
            );
        }

        ChatMediaStorageService.StoredMedia storedMedia =
                mediaStorageService.storeImage(imageBytes);

        ChatMessage message = new ChatMessage();
        message.setConversationId(conversationId);
        message.setSenderUserId(authenticatedUserId);
        message.setMessageType(ChatMessageType.IMAGE);
        message.setMediaStorageKey(storedMedia.storageKey());
        message.setMediaContentType(contentType.toLowerCase());
        message.setMediaSizeBytes(storedMedia.originalSizeBytes());
        message.setEncryptionKeyVersion(
                storedMedia.encryptionKeyVersion()
        );

        try {
            ChatMessage savedMessage = messageRepository.save(message);

            createRecipientReceipts(savedMessage, authenticatedUserId);

            return savedMessage;

        } catch (RuntimeException ex) {
            // Clean up the encrypted image if persistence fails.
            try {
                mediaStorageService.deleteImage(
                        storedMedia.storageKey()
                );
            } catch (RuntimeException cleanupException) {
                ex.addSuppressed(cleanupException);
            }

            throw ex;
        }
    }

    /**
     * Creates a receipt for each participant other than the sender.
     *
     * Receipt creation does not itself mean that the message
     * has been delivered or read.
     */
    private void createRecipientReceipts(
            ChatMessage message,
            Long senderUserId
    ) {
        List<ChatConversationParticipant> participants =
                participantRepository.findByConversationId(
                        message.getConversationId()
                );

        for (ChatConversationParticipant participant : participants) {
            Long recipientUserId = participant.getUserId();

            if (recipientUserId == null
                    || recipientUserId.equals(senderUserId)) {
                continue;
            }

            ChatMessageReceipt receipt = new ChatMessageReceipt();
            receipt.setMessageId(message.getId());
            receipt.setUserId(recipientUserId);

            receiptRepository.save(receipt);
        }
    }

    /**
     * Decrypts a text message only after checking conversation membership.
     */
    @Transactional(readOnly = true)
    public String decryptMessageForParticipant(
            Long authenticatedUserId,
            Long conversationId,
            Long messageId
    ) {
        validateIdentifiers(authenticatedUserId, conversationId);

        verifyParticipant(conversationId, authenticatedUserId);

        if (messageId == null || messageId <= 0) {
            throw new IllegalArgumentException(
                    "Valid message ID is required."
            );
        }

        ChatMessage message = messageRepository
                .findByIdAndConversationId(messageId, conversationId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Message not found."
                ));

        if (message.getDeletedAt() != null) {
            throw new IllegalArgumentException(
                    "Message not found."
            );
        }

        if (message.getMessageType() != ChatMessageType.TEXT && message.getMessageType() != ChatMessageType.SYSTEM) {
            throw new IllegalArgumentException(
                    "This message does not contain a text payload."
            );
        }

        return encryptionService.decrypt(
                message.getCiphertext(),
                message.getNonce(),
                message.getEncryptionKeyVersion()
        );
    }

    /**
     * Retrieves paginated messages after verifying conversation membership.
     *
     * Text content remains encrypted in the returned entities.
     */
    @Transactional(readOnly = true)
    public Page<ChatMessage> getConversationMessages(
            Long authenticatedUserId,
            Long conversationId,
            Pageable pageable
    ) {
        validateIdentifiers(authenticatedUserId, conversationId);

        if (pageable == null) {
            throw new IllegalArgumentException(
                    "Pagination information is required."
            );
        }

        verifyParticipant(conversationId, authenticatedUserId);

        return messageRepository.findByConversationIdAndDeletedAtIsNull(
                conversationId,
                pageable
        );
    }

    /**
     * Retrieves and decrypts an image after verifying conversation membership.
     */
    @Transactional(readOnly = true)
    public DownloadedImage getImageForParticipant(
            Long authenticatedUserId,
            Long conversationId,
            Long messageId
    ) {
        validateIdentifiers(authenticatedUserId, conversationId);

        if (messageId == null || messageId <= 0) {
            throw new IllegalArgumentException(
                    "Valid message ID is required."
            );
        }

        verifyParticipant(conversationId, authenticatedUserId);

        ChatMessage message = messageRepository
                .findByIdAndConversationId(messageId, conversationId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Image not found."
                ));

        if (message.getDeletedAt() != null) {
            throw new IllegalArgumentException(
                    "Image not found."
            );
        }

        if (message.getMessageType() != ChatMessageType.IMAGE) {
            throw new IllegalArgumentException(
                    "This message does not contain an image."
            );
        }

        String storageKey = message.getMediaStorageKey();

        if (storageKey == null || storageKey.isBlank()) {
            throw new IllegalStateException(
                    "Image storage information is missing."
            );
        }

        byte[] imageBytes = mediaStorageService.loadImage(
                storageKey,
                message.getEncryptionKeyVersion()
        );

        String contentType = message.getMediaContentType();

        if (contentType == null ||
                !(contentType.equalsIgnoreCase("image/jpeg")
                        || contentType.equalsIgnoreCase("image/png"))) {
            throw new IllegalStateException(
                    "Stored image content type is invalid."
            );
        }

        return new DownloadedImage(imageBytes, contentType);
    }

    private void validateIdentifiers(
            Long authenticatedUserId,
            Long conversationId
    ) {
        if (authenticatedUserId == null
                || conversationId == null
                || authenticatedUserId <= 0
                || conversationId <= 0) {
            throw new IllegalArgumentException(
                    "Valid user ID and conversation ID are required."
            );
        }
    }

    private void verifyParticipant(
            Long conversationId,
            Long userId
    ) {
        if (!participantRepository.existsByConversationIdAndUserId(
                conversationId,
                userId
        )) {
            throw new AccessDeniedException(
                    "You are not a participant in this conversation."
            );
        }
    }

    /**
     * Image bytes are returned only after authorization and decryption.
     */
    public record DownloadedImage(
            byte[] imageBytes,
            String contentType
    ) {
        public DownloadedImage {
            imageBytes = imageBytes.clone();
        }

        @Override
        public byte[] imageBytes() {
            return imageBytes.clone();
        }
    }

    @Transactional
    public int markConversationAsRead(
            Long authenticatedUserId,
            Long conversationId
    ) {
        validateIdentifiers(authenticatedUserId, conversationId);

        // Verify that the authenticated user belongs to this conversation.
        verifyParticipant(conversationId, authenticatedUserId);

        // Fetch messages that have not been deleted.
        List<Long> incomingMessageIds = messageRepository
                .findByConversationIdAndDeletedAtIsNull(
                        conversationId,
                        Pageable.unpaged()
                )
                .getContent()
                .stream()
                // Exclude messages sent by the authenticated user.
                .filter(message ->
                        !authenticatedUserId.equals(message.getSenderUserId())
                )
                .map(ChatMessage::getId)
                .toList();

        if (incomingMessageIds.isEmpty()) {
            return 0;
        }

        // Only update unread receipts belonging to this user.
        List<ChatMessageReceipt> unreadReceipts =
                receiptRepository
                        .findByUserIdAndMessageIdInAndReadAtIsNull(
                                authenticatedUserId,
                                incomingMessageIds
                        );

        if (unreadReceipts.isEmpty()) {
            return 0;
        }

        Instant readTime = Instant.now();

        unreadReceipts.forEach(receipt ->
                receipt.setReadAt(readTime)
        );

        receiptRepository.saveAll(unreadReceipts);

        return unreadReceipts.size();
    }

    @Transactional(readOnly = true)
    public long getUnreadCount(Long userId) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException(
                    "Valid user ID is required."
            );
        }

        return receiptRepository.countByUserIdAndReadAtIsNull(userId);
    }

}
