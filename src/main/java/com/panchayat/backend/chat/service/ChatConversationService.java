
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
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private com.panchayat.backend.sarpanch.SarpanchService sarpanchService;


    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private com.panchayat.backend.social.follow.FriendService friendService;


    @org.springframework.beans.factory.annotation.Autowired
    private ChatSocialService chatSocial;

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;


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

        // Sarpanch: always the one read-only system conversation
        if (sarpanchService.isSarpanch(recipientUserId)) {
            Long sys = sarpanchService.systemConversationFor(authenticatedUserId);
            return conversationRepository.findById(sys).orElseThrow();
        }

        // Find an existing conversation shared by both users.
        for (ChatConversationParticipant participant :
                participantRepository.findByUserId(authenticatedUserId)) {

            Long existingConversationId = participant.getConversationId();

            // A group that happens to have these two people is not their direct chat
            boolean recipientIsParticipant =
                    !chatSocial.isGroup(existingConversationId)
                    && participantRepository.existsByConversationIdAndUserId(
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
        participantRepository.flush();
        // Not friends: it's a message request for the receiver
        chatSocial.markNewDirectChat(conversation.getId(), authenticatedUserId, recipientUserId);

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
        java.util.Set<String> online = chatSocial.visibleOnlineEmails();
        return participantRepository.findByUserId(userId)
                .stream()
                .map(participant -> {
                    ChatConversation conversation = conversationRepository
                            .findById(participant.getConversationId())
                            .orElse(null);
                    if (conversation == null) {
                        return null;
                    }
                    java.util.Map<String, Object> meta = jdbc.queryForMap(
                            "select c.kind, c.name, p.role, p.muted, p.request_state, c.system_conversation, c.pinned,"
                                    + " (select count(*) from chat_conversation_participants x where x.conversation_id = c.id) as members"
                                    + " from chat_conversations c join chat_conversation_participants p on p.conversation_id = c.id and p.user_id = ?"
                                    + " where c.id = ?", userId, conversation.getId());
                    boolean group = "GROUP".equals(meta.get("kind"));

                    User otherUser = null;
                    if (!group) {
                        ChatConversationParticipant otherParticipant =
                                participantRepository
                                        .findByConversationId(conversation.getId())
                                        .stream()
                                        .filter(p -> !userId.equals(p.getUserId()))
                                        .findFirst()
                                        .orElse(null);
                        if (otherParticipant == null) {
                            return null;
                        }
                        otherUser = userRepository.findById(otherParticipant.getUserId()).orElse(null);
                        if (otherUser == null) {
                            return null;
                        }
                    }

                    ChatMessage lastMessage = messageRepository
                            .findFirstByConversationIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(
                                    conversation.getId()
                            )
                            .orElse(null);

                    String lastMessagePreview = null;
                    String lastMessageType = null;
                    Long lastSenderId = null;
                    Instant lastMessageAt = conversation.getUpdatedAt();

                    if (lastMessage != null) {
                        lastMessageType = lastMessage.getMessageType().name();
                        lastMessageAt = lastMessage.getCreatedAt();
                        lastSenderId = lastMessage.getSenderUserId();
                        if (lastMessage.getMessageType() == ChatMessageType.TEXT) {
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
                            // Room and game invites show as a short line in the list
                            if (lastMessagePreview != null && lastMessagePreview.startsWith(com.panchayat.backend.social.invites.InviteService.MARK)) {
                                lastMessagePreview = lastMessagePreview.contains("\"kind\":\"GAME\"") ? "🎮 Game invite" : "🎙 Room invite";
                            }
                        } else if (lastMessage.getMessageType() == ChatMessageType.SYSTEM) {
                            try {
                                String t = (String) sarpanchService.extras(java.util.List.of(lastMessage.getId())).getOrDefault(lastMessage.getId(), java.util.Map.of()).get("title");
                                String b = messageService.decryptMessageForParticipant(userId, conversation.getId(), lastMessage.getId());
                                lastMessagePreview = t != null ? t + ": " + b : b;
                            } catch (RuntimeException ex) {
                                lastMessagePreview = "Message from Sarpanch";
                            }
                        } else if (lastMessage.getMessageType() == ChatMessageType.IMAGE) {
                            lastMessagePreview = "📷 Photo";
                        }
                    }

                    // Message requests: yours to answer, or one you sent that's waiting
                    String myState = String.valueOf(meta.get("request_state"));
                    String theirState = "ACCEPTED";
                    if (!group && otherUser != null) {
                        if (chatSocial.friends(userId, otherUser.getId())) myState = "ACCEPTED";
                        else theirState = chatSocial.requestState(conversation.getId(), otherUser.getId());
                    }
                    if ("DECLINED".equals(myState)) return null;
                    // A request with nothing in it yet isn't shown to the receiver
                    if ("PENDING".equals(myState) && lastMessage == null) return null;

                    long unreadCount =
                            receiptRepository.countUnreadMessagesInConversation(
                                    userId,
                                    conversation.getId()
                            );

                    Long otherId = otherUser == null ? null : otherUser.getId();
                    String email = otherUser == null ? null : otherUser.getEmail();
                    return new ConversationListItem(
                            conversation.getId(),
                            otherId,
                            group ? (String) meta.get("name") : otherUser.getFullName(),
                            group ? null : com.panchayat.backend.admin.media.MediaUrls.protectedLink(otherUser.getAvatarUrl()),
                            lastMessagePreview,
                            lastMessageType,
                            lastMessageAt,
                            unreadCount,
                            group ? "GROUP" : "DIRECT",
                            ((Number) meta.get("members")).intValue(),
                            (String) meta.get("role"),
                            Boolean.TRUE.equals(meta.get("muted")),
                            lastSenderId,
                            otherId != null && chatSocial.blocked(userId, otherId),
                            otherId != null && chatSocial.blocked(otherId, userId),
                            email != null && online.contains(email.toLowerCase(java.util.Locale.ROOT)),
                            otherUser == null ? null : otherUser.getId() == null ? null : com.panchayat.backend.common.PublicIds.user(otherUser.getId()),
                            group ? "ACCEPTED" : myState,
                            group ? "ACCEPTED" : theirState,
                            Boolean.TRUE.equals(meta.get("system_conversation")),
                            Boolean.TRUE.equals(meta.get("pinned")),
                            otherUser != null && sarpanchService.isSarpanch(otherUser.getId()),
                            // The public room they're in now (if they show their online status)
                            otherId != null && email != null && online.contains(email.toLowerCase(java.util.Locale.ROOT)) ? friendService.publicRoomOf(otherId) : null
                    );
                })
                .filter(Objects::nonNull)
                .sorted(
                        // Pinned (Sarpanch) first, then newest first
                        Comparator.comparing(ConversationListItem::pinned).reversed()
                                .thenComparing(
                                        ConversationListItem::lastMessageAt,
                                        Comparator.nullsLast(Comparator.reverseOrder())
                                )
                )
                .toList();
    }

    /**
     * One chat in your list. Groups: otherUser* are null and otherUserName is the
     * group's name. blockedByMe / blockedMe and otherOnline are for direct chats.
     */
    public record ConversationListItem(
            Long conversationId,
            Long otherUserId,
            String otherUserName,
            String otherUserAvatar,
            String lastMessage,
            String lastMessageType,
            Instant lastMessageAt,
            long unreadCount,
            String kind,
            int memberCount,
            String myRole,
            boolean muted,
            Long lastSenderId,
            boolean blockedByMe,
            boolean blockedMe,
            boolean otherOnline,
            Long otherPublicId,
            /* PENDING: a message request waiting for your answer */
            String requestState,
            /* PENDING / DECLINED: your request to them is waiting (you can send 1 message) */
            String theirRequestState,
            /* The read-only Sarpanch conversation, pinned first */
            boolean systemConversation,
            boolean pinned,
            /* The other person is the official account (verified badge) */
            boolean official,
            /* { roomId, roomCode, name, type, coverUrl, game } or null */
            java.util.Map<String, Object> otherRoom
    ) {}
}
