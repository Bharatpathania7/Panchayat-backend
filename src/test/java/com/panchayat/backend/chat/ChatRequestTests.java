package com.panchayat.backend.chat;

import com.panchayat.backend.auth.SecurityConfig.JwtService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Message requests: a chat from someone who isn't your friend is a request
 * (they can send 1 message until you reply or accept); friends chat freely.
 * Also "Active now". Creates its own users and removes them afterwards.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChatRequestTests {

    @Value("${local.server.port}")
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    JwtService jwt;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private final List<Long> created = new ArrayList<>();
    private final Map<Long, String> tokens = new HashMap<>();

    record Res(int status, Object body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> map() {
            return (Map<String, Object>) body;
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list() {
            return (List<Map<String, Object>>) body;
        }

        String message() {
            return body instanceof Map<?, ?> m ? String.valueOf(m.get("message")) : String.valueOf(body);
        }
    }

    long user(String name) {
        String email = "chat-test-" + run + "-" + created.size() + "@test.local";
        Long id = jdbc.queryForObject("insert into users (email, full_name, email_verified, profile_completed) values (?, ?, true, true) returning id", Long.class, email, name);
        created.add(id);
        tokens.put(id, jwt.generateToken(id, email));
        return id;
    }

    @AfterAll
    void cleanup() {
        if (created.isEmpty()) return;
        String in = String.join(",", created.stream().map(String::valueOf).toList());
        jdbc.update("delete from chat_conversations where id in (select conversation_id from chat_conversation_participants where user_id in (" + in + "))");
        jdbc.update("delete from users where id in (" + in + ")");
    }

    Res call(long as, String method, String path, Object body) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + tokens.get(as));
            if (body != null) b.header("Content-Type", "application/json");
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Res(r.statusCode(), r.body() == null || r.body().isBlank() ? null : json.readValue(r.body(), Object.class));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    String open(long from, long to) {
        Res r = call(from, "POST", "/api/v1/chat/conversations", Map.of("recipientUserId", to));
        assertEquals(200, r.status(), r.message());
        return String.valueOf(r.map().get("id"));
    }

    int send(long from, String conv, String text) {
        return call(from, "POST", "/api/v1/chat/conversations/" + conv + "/messages", Map.of("message", text)).status();
    }

    Map<String, Object> inboxItem(long who, String conv) {
        return call(who, "GET", "/api/v1/chat/conversations", null).list().stream()
                .filter(c -> conv.equals(String.valueOf(c.get("conversationId")))).findFirst().orElse(null);
    }

    @Test
    void strangerMessagesAreRequestsWithOneMessageUntilAccepted() {
        long a = user("Asha"), b = user("Bilal");
        String conv = open(a, b);
        assertNull(inboxItem(b, conv), "an empty request isn't shown");
        assertEquals(201, send(a, conv, "Hi! Can we talk?"));
        Res second = call(a, "POST", "/api/v1/chat/conversations/" + conv + "/messages", Map.of("message", "Hello??"));
        assertEquals(403, second.status(), "only 1 message until accepted");
        assertTrue(second.message().contains("message request"), second.message());

        Map<String, Object> forB = inboxItem(b, conv);
        assertEquals("PENDING", forB.get("requestState"), "it's a request for Bilal");
        assertEquals("PENDING", inboxItem(a, conv).get("theirRequestState"), "Asha sees it waiting");

        // Accept: both chat freely
        assertEquals(200, call(b, "POST", "/api/v1/chat/conversations/" + conv + "/accept", null).status());
        assertEquals("ACCEPTED", inboxItem(b, conv).get("requestState"));
        assertEquals(201, send(a, conv, "Thanks for accepting"));
        assertEquals(201, send(b, conv, "Sure"));
    }

    @Test
    void replyingAcceptsAndDeletingHidesTheRequest() {
        long a = user("Chandni"), b = user("Dev"), c = user("Esha");
        String conv = open(a, b);
        send(a, conv, "Hey");
        assertEquals(201, send(b, conv, "Hi, who's this?"), "replying is allowed");
        assertEquals("ACCEPTED", inboxItem(b, conv).get("requestState"), "and accepts the request");
        assertEquals(201, send(a, conv, "Another message"));

        String conv2 = open(c, b);
        send(c, conv2, "Hello");
        assertEquals(200, call(b, "POST", "/api/v1/chat/conversations/" + conv2 + "/decline", null).status());
        assertNull(inboxItem(b, conv2), "deleted requests leave the list");
        assertEquals(403, send(c, conv2, "Again"), "and no more messages from Esha");
        assertEquals(403, call(a, "POST", "/api/v1/chat/conversations/" + conv2 + "/accept", null).status(), "outsiders can't answer it");
    }

    @Test
    void friendsChatFreelyAndShowInActiveNow() {
        long a = user("Farah"), b = user("Gopal");
        assertEquals(200, call(a, "POST", "/api/v1/friends/" + b + "/follow", null).status());
        assertEquals(200, call(b, "POST", "/api/v1/friends/" + a + "/follow", null).status());
        String conv = open(a, b);
        assertEquals(201, send(a, conv, "one"));
        assertEquals(201, send(a, conv, "two"));
        assertEquals(201, send(a, conv, "three"));
        assertEquals("ACCEPTED", inboxItem(b, conv).get("requestState"));
        // Nobody is connected in a test, so nobody is active; the call itself works
        Res active = call(a, "GET", "/api/v1/friends/active", null);
        assertEquals(200, active.status());
        assertTrue(active.body() instanceof List<?>);
    }
}
