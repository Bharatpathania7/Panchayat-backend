package com.panchayat.backend.security;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * Limits on the live connection: each connection may send 30 messages per
 * 10 seconds (room chat, typing, chat) and subscribe to 60 topics per minute.
 * Over the limit the message is refused and the app gets an error frame.
 */
@Component
public class StompRateLimitInterceptor implements ChannelInterceptor {

    private final RateLimiter limiter;

    public StompRateLimitInterceptor(RateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor a = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (a == null || a.getCommand() == null) return message;
        String session = a.getSessionId() == null ? "?" : a.getSessionId();
        if (a.getCommand() == StompCommand.SEND && limiter.hit("ws-send:" + session, 30, 10_000) > 0) {
            throw new MessagingException("Too many messages. Slow down.");
        }
        if (a.getCommand() == StompCommand.SUBSCRIBE && limiter.hit("ws-sub:" + session, 60, 60_000) > 0) {
            throw new MessagingException("Too many subscriptions.");
        }
        return message;
    }
}
