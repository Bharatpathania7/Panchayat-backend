package com.panchayat.backend.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * Limits on the live connection, without ever closing it:
 *  - SEND (room chat, typing, chat): 60 per 10 seconds per connection;
 *  - SUBSCRIBE: 600 per minute per connection (screens re-subscribe often).
 * Over the limit the extra message is quietly dropped (returning null). It is
 * never answered with an error: an error frame makes the app disconnect and
 * reconnect, which would turn a limit into a reconnect loop.
 */
@Component
public class StompRateLimitInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(StompRateLimitInterceptor.class);

    private final RateLimiter limiter;

    public StompRateLimitInterceptor(RateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor a = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (a == null || a.getCommand() == null) return message;
        String session = a.getSessionId() == null ? "?" : a.getSessionId();
        if (a.getCommand() == StompCommand.SEND && limiter.hit("ws-send:" + session, 60, 10_000) > 0) {
            log.debug("Dropped a live message over the limit (session {})", session);
            return null;
        }
        if (a.getCommand() == StompCommand.SUBSCRIBE && limiter.hit("ws-sub:" + session, 600, 60_000) > 0) {
            log.debug("Dropped a subscription over the limit (session {})", session);
            return null;
        }
        return message;
    }
}
