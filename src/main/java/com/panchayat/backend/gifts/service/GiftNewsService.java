package com.panchayat.backend.gifts.service;

import com.panchayat.backend.admin.media.MediaUrls;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-wide gift news: a gift the admin marked "Server notification" (gifts.announce)
 * is announced to everyone in the app on /topic/gift-news, with the sender's
 * own short message:
 *   { eventId, fromUserId, fromName, fromAvatar, toUserId, toName, toAvatar,
 *     giftId, count, coins, roomId, roomName, message, at }
 */
@Service
public class GiftNewsService {

    private static final Logger log = LoggerFactory.getLogger(GiftNewsService.class);
    public static final long ANNOUNCE_COINS = 20_000;
    public static final int MESSAGE_MAX = 50;

    private final JdbcTemplate jdbc;
    private final SimpMessagingTemplate messaging;
    /** Gifts already announced (a retried send isn't announced twice). */
    private final Set<String> announced = ConcurrentHashMap.newKeySet();

    public GiftNewsService(JdbcTemplate jdbc, SimpMessagingTemplate messaging) {
        this.jdbc = jdbc;
        this.messaging = messaging;
    }

    public void announce(RoomGiftService.GiftSent g, String message) {
        // Only gifts the admin switched on ("Server notification") go to everyone, whatever the count
        if (g == null || g.fromBag()) return;
        Boolean on = jdbc.query("select announce from gifts where id = ?", rs -> rs.next() ? rs.getBoolean(1) : Boolean.FALSE, g.giftId());
        if (!Boolean.TRUE.equals(on)) return;
        if (!announced.add(g.eventId())) return;
        if (announced.size() > 5000) announced.clear();
        try {
            Map<String, Object> from = person(Long.valueOf(g.fromUserId()));
            Map<String, Object> to = person(Long.valueOf(g.toUserId()));
            String roomName = jdbc.query("select name from voice_rooms where id = ?", rs -> rs.next() ? rs.getString(1) : null, Long.valueOf(g.roomId()));
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("eventId", g.eventId());
            n.put("fromUserId", g.fromUserId());
            n.put("fromName", from.get("name"));
            n.put("fromAvatar", from.get("avatar"));
            n.put("toUserId", g.toUserId());
            n.put("toName", to.get("name"));
            n.put("toAvatar", to.get("avatar"));
            n.put("giftId", g.giftId());
            n.put("count", g.count());
            n.put("coins", g.coins());
            n.put("roomId", g.roomId());
            n.put("roomName", roomName);
            n.put("message", clean(message));
            n.put("at", System.currentTimeMillis());
            messaging.convertAndSend("/topic/gift-news", (Object) n);
        } catch (Exception e) {
            log.debug("Gift news not sent: {}", e.getMessage());
        }
    }

    private Map<String, Object> person(Long id) {
        Map<String, Object> m = new HashMap<>();
        jdbc.query("select full_name, avatar_url from users where id = ?", rs -> {
            m.put("name", rs.getString(1));
            m.put("avatar", MediaUrls.link(rs.getString(2)));
        }, id);
        return m;
    }

    /** One line, no control characters, at most MESSAGE_MAX characters. */
    static String clean(String s) {
        if (s == null) return "";
        String t = s.replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s+", " ").strip();
        return t.length() > MESSAGE_MAX ? t.substring(0, MESSAGE_MAX) : t;
    }
}
