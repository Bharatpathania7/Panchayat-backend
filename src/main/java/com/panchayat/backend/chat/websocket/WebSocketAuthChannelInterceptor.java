
        package com.panchayat.backend.chat.websocket;

import com.panchayat.backend.auth.SecurityConfig.JwtService;
import com.panchayat.backend.auth.repository.UserRepository;
import com.panchayat.backend.voice.entity.VoiceRoom;
import com.panchayat.backend.voice.repository.VoiceRoomParticipantRepository;
import com.panchayat.backend.voice.repository.VoiceRoomRepository;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class WebSocketAuthChannelInterceptor implements ChannelInterceptor {

    private static final Pattern VOICE_CHAT_TOPIC =
            Pattern.compile("^/topic/voice-rooms/(\\d+)/chat$");

    private static final Pattern VOICE_CHAT_APP_DESTINATION =
            Pattern.compile("^/app/voice-rooms/(\\d+)/chat$");

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final VoiceRoomRepository voiceRoomRepository;
    private final VoiceRoomParticipantRepository participantRepository;

    public WebSocketAuthChannelInterceptor(
            JwtService jwtService,
            UserRepository userRepository,
            VoiceRoomRepository voiceRoomRepository,
            VoiceRoomParticipantRepository participantRepository
    ) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.voiceRoomRepository = voiceRoomRepository;
        this.participantRepository = participantRepository;
    }

    @Override
    public Message<?> preSend(
            Message<?> message,
            MessageChannel channel
    ) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(
                        message,
                        StompHeaderAccessor.class
                );

        if (accessor == null) {
            return message;
        }

        StompCommand command = accessor.getCommand();

        if (StompCommand.CONNECT.equals(command)) {
            authenticate(accessor);
            return message;
        }

        if (command == null) {
            return message;
        }

        if (StompCommand.SEND.equals(command)
                || StompCommand.SUBSCRIBE.equals(command)
                || StompCommand.UNSUBSCRIBE.equals(command)) {

            if (accessor.getUser() == null) {
                throw new IllegalArgumentException(
                        "Authenticated WebSocket session required."
                );
            }
        }

        /*
         * Clients may publish only to application destinations.
         * Voice-room membership is checked again in the chat service.
         * A refused frame is dropped (null), never answered with an error:
         * an error frame closes the whole connection and the app would
         * reconnect in a loop.
         */
        if (StompCommand.SEND.equals(command)) {
            String destination = accessor.getDestination();

            if (destination == null
                    || !destination.startsWith("/app/")) {
                return null;
            }
        }

        if (StompCommand.SUBSCRIBE.equals(command)) {
            return allowedSubscription(accessor) ? message : null;
        }

        return message;
    }

    /** Private queues the app listens on; Spring delivers them only to their own user. */
    private static final Pattern OWN_QUEUE =
            Pattern.compile("^/user/queue/(chat|wallet|rewards|notify|game|cp|chat\\.typing|chat\\.events)$");

    /** A Ludo table and its chat: /topic/ludo/{gameId} and /topic/ludo/{gameId}/chat. */
    private static final Pattern LUDO_TOPIC =
            Pattern.compile("^/topic/ludo/(\\d+)(/chat)?$");

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate ludoJdbc;

    /** A room's live topics other than its chat. */
    private static final Pattern ROOM_TOPIC =
            Pattern.compile("^/topic/voice-rooms/(\\d+)/(state|info|gifts|media|pk|auction|redpacket|game|emote)$");

    /**
     * Who may listen to what:
     *  - your own private queues: always;
     *  - online status: any signed-in user;
     *  - a room's chat: only people who joined it;
     *  - a room's other topics (seats, settings, gifts, media, PK, auction):
     *    the room must be live; a private room only for its owner or people in it.
     * Everything else is refused.
     */
    private boolean allowedSubscription(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null) return false;
        if (OWN_QUEUE.matcher(destination).matches()) return true;
        if ("/topic/presence".equals(destination) || "/topic/redpackets".equals(destination) || "/topic/gift-news".equals(destination)) return true;

        Matcher chat = VOICE_CHAT_TOPIC.matcher(destination);
        Matcher topic = ROOM_TOPIC.matcher(destination);
        Matcher ludo = LUDO_TOPIC.matcher(destination);
        boolean isChat = chat.matches();
        boolean isLudo = ludo.matches();
        if (!isChat && !isLudo && !topic.matches()) return false;

        Long roomId;
        try {
            if (isLudo) {
                // A Ludo table follows its room: its chat needs you in the room, like room chat
                Long gameId = parseId(ludo.group(1));
                roomId = ludoJdbc.query("select room_id from room_games where id = ? and game = 'LUDO'", rs -> rs.next() ? rs.getLong(1) : null, gameId);
                if (roomId == null) return false;
                if (ludo.group(2) != null) isChat = true;
            } else {
                roomId = parseId(isChat ? chat.group(1) : topic.group(1));
            }
        } catch (IllegalArgumentException e) {
            return false;
        }
        Principal principal = accessor.getUser();
        if (principal == null || principal.getName() == null) return false;
        var user = userRepository.findByEmailIgnoreCase(principal.getName()).orElse(null);
        if (user == null) return false;
        VoiceRoom room = voiceRoomRepository.findById(roomId).orElse(null);
        if (room == null || !"ACTIVE".equals(room.getStatus())) return false;

        boolean joined = participantRepository
                .findByRoomIdAndUserIdAndStatus(roomId, user.getId(), "JOINED")
                .isPresent();
        if (isChat) return joined;
        // Room updates (also how a removed person learns it): live room, and for private rooms the owner or people in it
        return !room.isPrivate() || joined || room.getOwnerId().equals(user.getId());
    }

    private void authenticate(StompHeaderAccessor accessor) {
        String authorization =
                accessor.getFirstNativeHeader("Authorization");

        if (authorization == null
                || !authorization.startsWith("Bearer ")) {
            throw new IllegalArgumentException(
                    "Missing WebSocket Bearer token."
            );
        }

        String token = authorization.substring(7).trim();

        if (token.isEmpty() || !jwtService.isValid(token)) {
            throw new IllegalArgumentException(
                    "Invalid or expired WebSocket token."
            );
        }

        String accountType = jwtService.extractAccountType(token);

        if (accountType == null
                || !"USER".equals(
                accountType.toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException(
                    "Only regular user accounts can use chat."
            );
        }

        Long userId = jwtService.extractUserId(token);
        String email = jwtService.extractEmail(token);

        if (userId == null || userId <= 0
                || email == null || email.isBlank()) {
            throw new IllegalArgumentException(
                    "Invalid WebSocket user identity."
            );
        }

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        email,
                        null,
                        List.of(
                                new SimpleGrantedAuthority("ROLE_USER")
                        )
                );

        accessor.setUser(authentication);
    }

    private Long parseId(String value) {
        try {
            long id = Long.parseLong(value);

            if (id <= 0) {
                throw new IllegalArgumentException(
                        "Invalid voice room ID."
                );
            }

            return id;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Invalid voice room ID."
            );
        }
    }
}
