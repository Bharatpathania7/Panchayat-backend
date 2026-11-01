
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
         */
        if (StompCommand.SEND.equals(command)) {
            String destination = accessor.getDestination();

            if (destination == null
                    || !destination.startsWith("/app/")) {
                throw new IllegalArgumentException(
                        "Messages can only be sent to application destinations."
                );
            }
        }

        /*
         * Preserve personal chat subscriptions.
         * Voice-room subscriptions require an active room and JOINED membership.
         */
        if (StompCommand.SUBSCRIBE.equals(command)) {
            String destination = accessor.getDestination();

            if ("/user/queue/chat".equals(destination)) {
                return message;
            }

            Matcher matcher = destination == null
                    ? null
                    : VOICE_CHAT_TOPIC.matcher(destination);

            if (matcher == null || !matcher.matches()) {
                throw new IllegalArgumentException(
                        "Subscription to this destination is not allowed."
                );
            }

            Long roomId = parseId(matcher.group(1));

            Principal principal = accessor.getUser();

            if (principal == null || principal.getName() == null) {
                throw new IllegalArgumentException(
                        "Authenticated user identity is missing."
                );
            }

            var user = userRepository
                    .findByEmailIgnoreCase(principal.getName())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Authenticated user not found."
                    ));

            VoiceRoom room = voiceRoomRepository.findById(roomId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Voice room not found."
                    ));

            if (!"ACTIVE".equals(room.getStatus())) {
                throw new IllegalArgumentException(
                        "Voice room is not active."
                );
            }

            boolean isJoined = participantRepository
                    .findByRoomIdAndUserIdAndStatus(
                            roomId,
                            user.getId(),
                            "JOINED"
                    )
                    .isPresent();

            if (!isJoined) {
                throw new IllegalArgumentException(
                        "You must join this voice room before subscribing to its chat."
                );
            }
        }

        return message;
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
