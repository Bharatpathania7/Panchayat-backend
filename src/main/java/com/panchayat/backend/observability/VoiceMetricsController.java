package com.panchayat.backend.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * Timings the app measures on the phone: how long entering a room, connecting
 * voice, publishing the mic and reconnecting really took, and whether audio
 * went direct or through TURN. The server can't see any of these.
 *
 * Only a fixed set of names is accepted, and only numbers and fixed words are
 * logged, so nothing a client sends (and no token or ICE detail) reaches the logs.
 */
@RestController
@RequestMapping("/api/v1/voice-metrics")
public class VoiceMetricsController {

    private static final Logger log = LoggerFactory.getLogger(VoiceMetricsController.class);

    private static final Set<String> TIMED = Set.of("room_join", "voice_connect", "mic_publish", "reconnect");
    private static final Set<String> COUNTED = Set.of("connect_failed", "reconnect_gave_up", "media_path");
    private static final Set<String> PATHS = Set.of("direct_udp", "direct_tcp", "turn_udp", "turn_tcp", "turn_tls", "unknown");
    private static final Set<String> REASONS = Set.of("network", "timeout", "not_allowed", "server", "mic_denied", "mic_other", "dropped", "unknown");
    private static final int MAX_EVENTS = 20;
    private static final long MAX_MS = 15 * 60_000L;

    private final MeterRegistry metrics;

    public VoiceMetricsController(MeterRegistry metrics) {
        this.metrics = metrics;
    }

    public record ClientEvent(String name, Long ms, String path, String reason) {
    }

    @PostMapping
    public ResponseEntity<Void> report(@RequestBody(required = false) List<ClientEvent> events) {
        if (events != null) {
            events.stream().limit(MAX_EVENTS).filter(e -> e != null && e.name() != null).forEach(this::record);
        }
        return ResponseEntity.noContent().build();
    }

    private void record(ClientEvent e) {
        if (TIMED.contains(e.name()) && e.ms() != null && e.ms() >= 0 && e.ms() <= MAX_MS) {
            metrics.timer("panchayat.voice." + e.name()).record(Duration.ofMillis(e.ms()));
            log.info("voice_metric name={} ms={}", e.name(), e.ms());
        } else if (COUNTED.contains(e.name())) {
            String detail = "media_path".equals(e.name())
                    ? (PATHS.contains(e.path()) ? e.path() : "unknown")
                    : (REASONS.contains(e.reason()) ? e.reason() : "unknown");
            metrics.counter("panchayat.voice." + e.name(), "kind", detail).increment();
            log.info("voice_metric name={} kind={}", e.name(), detail);
        }
    }
}
