package com.panchayat.backend.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * One line per minute in the backend's log with what happened in that minute:
 * API response times, 5xx and 429 counts, auth limits hit, voice timings from
 * phones, and how busy the database pool is. No metrics port is opened and
 * nothing new is exposed: journalctl -u <service> | grep metrics_summary
 *
 * Database latency here is time waiting for and holding a pooled connection
 * (no SQL is logged: bound values could include tokens or hashes).
 */
@Component
public class MetricsSummaryLogger {

    private static final Logger log = LoggerFactory.getLogger("metrics_summary");

    private final MeterRegistry registry;
    private final long seconds;
    private final Map<String, double[]> last = new HashMap<>();
    private ScheduledExecutorService worker;

    public MetricsSummaryLogger(MeterRegistry registry, @Value("${app.metrics.summary-seconds:60}") long seconds) {
        this.registry = registry;
        this.seconds = seconds;
    }

    @PostConstruct
    void start() {
        if (seconds <= 0) return;
        worker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "metrics-summary");
            t.setDaemon(true);
            return t;
        });
        worker.scheduleWithFixedDelay(this::logSummary, seconds, seconds, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        if (worker != null) worker.shutdownNow();
    }

    void logSummary() {
        try {
            log.info(summary());
        } catch (Exception e) {
            log.warn("Couldn't build the summary: {}", e.getMessage());
        }
    }

    String summary() {
        StringBuilder out = new StringBuilder("metrics_summary");
        timers(out, "api", registry.find("http.server.requests").timers());
        count(out, "api_5xx", registry.find("http.server.requests").tag("outcome", "SERVER_ERROR").timers());
        count(out, "api_429", registry.find("http.server.requests").tag("status", "429").timers());
        counters(out, "auth_429", registry.find("panchayat.auth.rate_limited").counters());
        for (String name : List.of("room_join", "voice_connect", "mic_publish", "reconnect")) {
            timers(out, name, registry.find("panchayat.voice." + name).timers());
        }
        counters(out, "connect_failed", registry.find("panchayat.voice.connect_failed").counters());
        counters(out, "reconnect_gave_up", registry.find("panchayat.voice.reconnect_gave_up").counters());
        for (String kind : List.of("direct_udp", "direct_tcp", "turn_udp", "turn_tcp", "turn_tls", "unknown")) {
            counters(out, "path_" + kind, registry.find("panchayat.voice.media_path").tag("kind", kind).counters());
        }
        counters(out, "grace_expired", registry.find("panchayat.voice.grace_expired").counters());
        counters(out, "cache_hit", registry.find("panchayat.cache").tag("result", "hit").counters());
        counters(out, "cache_miss", registry.find("panchayat.cache").tag("result", "miss").counters());
        counters(out, "redis_errors", registry.find("panchayat.cache.redis_errors").counters());
        timers(out, "db_conn_wait", registry.find("hikaricp.connections.acquire").timers());
        timers(out, "db_conn_held", registry.find("hikaricp.connections.usage").timers());
        gauge(out, "db_active", registry.find("hikaricp.connections.active").gauge());
        gauge(out, "db_pending", registry.find("hikaricp.connections.pending").gauge());
        gauge(out, "db_max", registry.find("hikaricp.connections.max").gauge());
        return out.toString();
    }

    /** Count and mean for this interval (the meters only ever add up). */
    private void timers(StringBuilder out, String label, Iterable<Timer> timers) {
        double n = 0, ms = 0;
        for (Timer t : timers) {
            n += t.count();
            ms += t.totalTime(TimeUnit.MILLISECONDS);
        }
        double[] before = last.getOrDefault(label, new double[]{0, 0});
        last.put(label, new double[]{n, ms});
        double dn = n - before[0];
        out.append(' ').append(label).append("_n=").append((long) dn);
        if (dn > 0) {
            out.append(' ').append(label).append("_mean_ms=")
                    .append(String.format(Locale.ROOT, "%.1f", (ms - before[1]) / dn));
        }
    }

    private void count(StringBuilder out, String label, Iterable<Timer> timers) {
        double n = 0;
        for (Timer t : timers) n += t.count();
        delta(out, label, n);
    }

    private void counters(StringBuilder out, String label, Iterable<Counter> counters) {
        double n = 0;
        for (Counter c : counters) n += c.count();
        delta(out, label, n);
    }

    private void delta(StringBuilder out, String label, double n) {
        double[] before = last.getOrDefault(label, new double[]{0});
        last.put(label, new double[]{n});
        out.append(' ').append(label).append('=').append((long) (n - before[0]));
    }

    private static void gauge(StringBuilder out, String label, Gauge g) {
        if (g != null) out.append(' ').append(label).append('=').append((long) g.value());
    }
}
