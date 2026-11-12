package com.panchayat.backend.push;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Push notifications for the Android app (through Expo's push service). A phone registers its token after the person
 * signs in; when something happens to that person (a message, a gift, a new follower, a Sarpanch notice) and they are
 * not online in the app right now, the phone gets a notification. People who are online already see it live.
 */
@Service
public class PushService {

    private static final Logger log = LoggerFactory.getLogger(PushService.class);
    private static final String EXPO_URL = "https://exp.host/--/api/v2/push/send";
    private static final Pattern TOKEN = Pattern.compile("^(Exponent|Expo)PushToken\\[[A-Za-z0-9_\\-]{10,100}\\]$");

    private final JdbcTemplate jdbc;
    private final ObjectProvider<SimpUserRegistry> registry;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(2000), r -> {
        Thread t = new Thread(r, "push-send");
        t.setDaemon(true);
        return t;
    }, new ThreadPoolExecutor.DiscardOldestPolicy());

    public PushService(JdbcTemplate jdbc, ObjectProvider<SimpUserRegistry> registry) {
        this.jdbc = jdbc;
        this.registry = registry;
    }

    public void register(Long userId, String token, String platform) {
        String t = token == null ? "" : token.strip();
        if (!TOKEN.matcher(t).matches()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That isn't a valid push token.");
        String p = "ios".equalsIgnoreCase(platform) ? "ios" : "android";
        jdbc.update("insert into push_devices (user_id, token, platform) values (?, ?, ?) on conflict (token) do update set user_id = excluded.user_id, platform = excluded.platform, last_seen = now()", userId, t, p);
        // A phone belongs to one account at a time; more than 10 phones per account keeps only the newest
        jdbc.update("delete from push_devices where user_id = ? and id not in (select id from push_devices where user_id = ? order by last_seen desc limit 10)", userId, userId);
    }

    public void unregister(Long userId, String token) {
        if (token == null || token.isBlank()) return;
        jdbc.update("delete from push_devices where user_id = ? and token = ?", userId, token.strip());
    }

    private boolean online(String email) {
        try {
            SimpUserRegistry r = registry.getIfAvailable();
            if (r == null || email == null) return false;
            SimpUser u = r.getUser(email);
            return u != null && u.hasSessions();
        } catch (Exception e) {
            return false;
        }
    }

    /** A notification for one person, when they are not online in the app. Never throws. */
    public void toUser(Long userId, String title, String body, Map<String, Object> data) {
        try {
            if (userId == null) return;
            String email = jdbc.query("select email from users where id = ?", rs -> rs.next() ? rs.getString(1) : null, userId);
            if (email == null || online(email)) return;
            send(userId, title, body, data);
        } catch (Exception e) {
            log.debug("Push not queued for {}: {}", userId, e.getMessage());
        }
    }

    public void toEmail(String email, String title, String body, Map<String, Object> data) {
        try {
            if (email == null || online(email)) return;
            Long id = jdbc.query("select id from users where lower(email) = lower(?)", rs -> rs.next() ? rs.getLong(1) : null, email);
            if (id != null) send(id, title, body, data);
        } catch (Exception e) {
            log.debug("Push not queued: {}", e.getMessage());
        }
    }

    /** Sends even if the person is online (a test, or something that must reach the phone). */
    public int forceToUser(Long userId, String title, String body, Map<String, Object> data) {
        return send(userId, title, body, data);
    }

    private int send(Long userId, String title, String body, Map<String, Object> data) {
        List<String> tokens = jdbc.queryForList("select token from push_devices where user_id = ?", String.class, userId);
        if (tokens.isEmpty()) return 0;
        String t = cut(title, 80), b = cut(body, 200);
        List<Map<String, Object>> msgs = new ArrayList<>();
        for (String tok : tokens) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("to", tok);
            m.put("title", t);
            m.put("body", b);
            m.put("data", data == null ? Map.of() : data);
            m.put("sound", "default");
            m.put("channelId", "default");
            m.put("priority", "high");
            m.put("ttl", 3600);
            msgs.add(m);
        }
        worker.execute(() -> post(msgs));
        return tokens.size();
    }

    private void post(List<Map<String, Object>> msgs) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(EXPO_URL)).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json").header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(msgs), StandardCharsets.UTF_8)).build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() / 100 != 2) {
                log.warn("Expo push answered {}", res.statusCode());
                return;
            }
            JsonNode data = json.readTree(res.body()).path("data");
            for (int i = 0; i < data.size() && i < msgs.size(); i++) {
                JsonNode r = data.get(i);
                if ("error".equals(r.path("status").asText()) && "DeviceNotRegistered".equals(r.path("details").path("error").asText())) {
                    jdbc.update("delete from push_devices where token = ?", (String) msgs.get(i).get("to"));
                }
            }
        } catch (Exception e) {
            log.debug("Expo push failed: {}", e.getMessage());
        }
    }

    private static String cut(String v, int max) {
        String s = v == null ? "" : v.strip().replaceAll("\\s+", " ");
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }
}
