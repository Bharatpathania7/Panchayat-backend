
        package com.panchayat.backend.chat.service;

import com.panchayat.backend.auth.entity.User;
import com.panchayat.backend.auth.repository.UserRepository;
import com.panchayat.backend.chat.entity.*;
import com.panchayat.backend.chat.repository.ChatConversationParticipantRepository;
import com.panchayat.backend.chat.repository.ChatConversationRepository;
import org.springframework.stereotype.Service;


import java.time.Instant;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Objects;

import com.panchayat.backend.chat.repository.ChatMessageRepository;
import com.panchayat.backend.chat.repository.ChatMessageReceiptRepository;
import java.util.Comparator;

@Service
public class ChatConversationService {

    private final ChatConversationRepository conversationRepository;
    private final ChatConversationParticipantRepository participantRepository;
    private final UserRepository userRepository;
    private final ChatMessageRepository messageRepository;
    private final ChatMessageReceiptRepository receiptRepository;
    private final ChatMessageService messageService;

    public ChatConversationService(
            ChatConversationRepository conversationRepository,
            ChatConversationParticipantRepository participantRepository,
            UserRepository userRepository,
            ChatMessageRepository messageRepository ,
            ChatMessageReceiptRepository receiptRepository,
            ChatMessageService messageService
    ) {
        this.conversationRepository = conversationRepository;
        this.participantRepository = participantRepository;
        this.userRepository = userRepository;
        this.messageRepository = messageRepository;
        this.receiptRepository = receiptRepository;
        this.messageService = messageService;
    }

    @Transactional
    public ChatConversation getOrCreateConversation(
            Long authenticatedUserId,
            Long recipientUserId
    ) {
        if (authenticatedUserId == null || recipientUserId == null) {
            throw new IllegalArgumentException(
                    "Both user IDs are required."
            );
        }

        if (authenticatedUserId.equals(recipientUserId)) {
            throw new IllegalArgumentException(
                    "You cannot start a personal chat with yourself."
            );
        }

        // Verify that both users exist.
        userRepository.findById(authenticatedUserId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Authenticated user was not found."
                ));

        userRepository.findById(recipientUserId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Recipient user was not found."
                ));

        // Find an existing conversation shared by both users.
        for (ChatConversationParticipant participant :
                participantRepository.findByUserId(authenticatedUserId)) {

            Long existingConversationId = participant.getConversationId();

            boolean recipientIsParticipant =
                    participantRepository.existsByConversationIdAndUserId(
                            existingConversationId,
                            recipientUserId
                    );

            if (recipientIsParticipant) {
                long participantCount =
                        participantRepository
                                .findByConversationId(existingConversationId)
                                .size();

                if (participantCount == 2) {
                    return conversationRepository
                            .findById(existingConversationId)
                            .orElseThrow(() -> new IllegalStateException(
                                    "Conversation record is missing."
                            ));
                }
            }
        }

        // Create a new personal conversation.
        ChatConversation conversation =
                conversationRepository.save(new ChatConversation());

        addParticipant(conversation.getId(), authenticatedUserId);
        addParticipant(conversation.getId(), recipientUserId);

        return conversation;
    }

    private void addParticipant(Long conversationId, Long userId) {
        ChatConversationParticipant participant =
                new ChatConversationParticipant();

        participant.setConversationId(conversationId);
        participant.setUserId(userId);

        participantRepository.save(participant);
    }
    @Transactional(readOnly = true)
    public List<ChatConversation> getUserConversations(Long userId) {

        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("Valid user ID is required.");
        }

        userRepository.findById(userId)
                .orElseThrow(() ->
                        new IllegalArgumentException("User not found."));

        return participantRepository.findByUserId(userId)
                .stream()
                .map(participant ->
                        conversationRepository.findById(
                                participant.getConversationId()
                        ).orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
    }
    @Transactional(readOnly = true)
    public List<ConversationListItem> getConversationList(Long userId) {

        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("Valid user ID is required.");
        }

        return participantRepository.findByUserId(userId)
                .stream()
                .map(participant -> {

                    ChatConversation conversation = conversationRepository
                            .findById(participant.getConversationId())
                            .orElse(null);

                    if (conversation == null) {
                        return null;
                    }

                    ChatConversationParticipant otherParticipant =
                            participantRepository
                                    .findByConversationId(conversation.getId())
                                    .stream()
                                    .filter(p ->
                                            !userId.equals(p.getUserId()))
                                    .findFirst()
                                    .orElse(null);

                    if (otherParticipant == null) {
                        return null;
                    }

                    User otherUser = userRepository
                            .findById(otherParticipant.getUserId())
                            .orElse(null);

                    if (otherUser == null) {
                        return null;
                    }

                    ChatMessage lastMessage = messageRepository
                            .findFirstByConversationIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(
                                    conversation.getId()
                            )
                            .orElse(null);

                    String lastMessagePreview = null;
                    String lastMessageType = null;
                    Instant lastMessageAt = conversation.getUpdatedAt();

                    if (lastMessage != null) {

                        lastMessageType =
                                lastMessage.getMessageType().name();

                        lastMessageAt = lastMessage.getCreatedAt();

                        if (lastMessage.getMessageType()
                                == ChatMessageType.TEXT) {

                            try {
                                lastMessagePreview =
                                        messageService.decryptMessageForParticipant(
                                                userId,
                                                conversation.getId(),
                                                lastMessage.getId()
                                        );
                            } catch (RuntimeException ex) {
                                lastMessagePreview = "Message unavailable";
                            }

                        } else if (lastMessage.getMessageType()
                                == ChatMessageType.IMAGE) {

                            lastMessagePreview = "📷 Photo";
                        }
                    }

                    long unreadCount =
                            receiptRepository.countUnreadMessagesInConversation(
                                    userId,
                                    conversation.getId()
                            );

                    return new ConversationListItem(
                            conversation.getId(),
                            otherUser.getId(),
                            otherUser.getFullName(),
                            otherUser.getAvatarUrl(),
                            lastMessagePreview,
                            lastMessageType,
                            lastMessageAt,
                            unreadCount
                    );
                })
                .filter(Objects::nonNull)
                .sorted(
                        Comparator.comparing(
                                ConversationListItem::lastMessageAt,
                                Comparator.nullsLast(Comparator.reverseOrder())
                        )
                )
                .toList();
    }

    public record ConversationListItem(
            Long conversationId,
            Long otherUserId,
            String otherUserName,
            String otherUserAvatar,
            String lastMessage,
            String lastMessageType,
            Instant lastMessageAt,
            long unreadCount
    ) {}
}
