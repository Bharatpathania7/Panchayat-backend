
        package com.panchayat.backend.chat.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    private final WebSocketAuthChannelInterceptor authInterceptor;
    private final com.panchayat.backend.security.StompRateLimitInterceptor stompLimits;

    /** Sites allowed to open the live connection (app.ws.allowed-origins / WS_ALLOWED_ORIGINS, comma separated). */
    private final String[] allowedOrigins;

    public WebSocketConfig(
            WebSocketAuthChannelInterceptor authInterceptor,
            com.panchayat.backend.security.StompRateLimitInterceptor stompLimits,
            @org.springframework.beans.factory.annotation.Value("${app.ws.allowed-origins:http://localhost:3000,http://localhost:3100,http://localhost:5173,https://panchayat.world,https://www.panchayat.world,https://admin.panchayat.world}")
            String allowedOrigins
    ) {
        this.authInterceptor = authInterceptor;
        this.stompLimits = stompLimits;
        this.allowedOrigins = java.util.Arrays.stream(allowedOrigins.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Private messages delivered to authenticated users.
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");

        // Voice-room chat broadcasts.
        // Heartbeats both ways every 20 s, so dead connections are noticed and closed
        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[]{20_000, 20_000})
                .setTaskScheduler(heartbeatScheduler());
    }

    private org.springframework.scheduling.TaskScheduler heartbeatScheduler() {
        org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler s = new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        s.setPoolSize(2);
        s.setThreadNamePrefix("ws-heartbeat-");
        s.initialize();
        return s;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(allowedOrigins);
    }
    @Override
    public void configureClientInboundChannel(
            ChannelRegistration registration
    ) {
        // Who you are first, then how much you may send
        registration.interceptors(authInterceptor, stompLimits);
        // Inbound work runs on a bounded pool: a burst queues instead of exhausting threads
        registration.taskExecutor().corePoolSize(8).maxPoolSize(64).queueCapacity(10_000);
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.taskExecutor().corePoolSize(8).maxPoolSize(64).queueCapacity(10_000);
    }

    /** Frames up to 64 KB; a slow phone gets up to 512 KB buffered or 20 s before it's dropped. */
    @Override
    public void configureWebSocketTransport(org.springframework.web.socket.config.annotation.WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(64 * 1024)
                .setSendBufferSizeLimit(512 * 1024)
                .setSendTimeLimit(20_000);
    }
}
