package com.panchayat.backend.rooms.service;

import com.panchayat.backend.chat.service.ChatSocialService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Takes people out of rooms when they closed the app without pressing Leave.
 *
 * Every minute: anyone marked JOINED in a room who has had no live connection
 * (no app socket) for two checks in a row, about 2 minutes, is marked LEFT and
 * leaves their seat, the same as pressing Leave. Two checks give a quick
 * reconnect, or a server restart, time to come back first. This keeps a room's
 * Online list to people who are really there, and stops voice EXP for people
 * who aren't.
 */
@Component
public class StaleParticipantSweeper {

    private static final Logger log = LoggerFactory.getLogger(StaleParticipantSweeper.class);
    private static final int MISSES_BEFORE_LEAVING = 2;

    private final JdbcTemplate jdbc;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "room-sweeper");
        t.setDaemon(true);
        return t;
    });
    /** participant row id -> checks in a row they were missing */
    private final Map<Long, Integer> misses = new ConcurrentHashMap<>();

    @Autowired @Lazy private ChatSocialService social;
    @Autowired @Lazy private RoomStateService roomState;

    public StaleParticipantSweeper(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        worker.scheduleWithFixedDelay(this::sweep, 90, 60, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        worker.shutdownNow();
    }

    void sweep() {
        try {
            Set<String> online = social.onlineEmails();
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "select p.id, p.room_id, p.user_id, lower(u.email) as email from voice_room_participants p join users u on u.id = p.user_id"
                            + " where p.status = 'JOINED' and p.joined_at < now() - interval '2 minutes' limit 20000");
            Set<Long> seen = new HashSet<>();
            for (Map<String, Object> r : rows) {
                long id = ((Number) r.get("id")).longValue();
                String email = (String) r.get("email");
                if (email != null && online.contains(email)) {
                    misses.remove(id);
                    continue;
                }
                seen.add(id);
                int n = misses.merge(id, 1, Integer::sum);
                if (n < MISSES_BEFORE_LEAVING) continue;
                misses.remove(id);
                long roomId = ((Number) r.get("room_id")).longValue();
                long userId = ((Number) r.get("user_id")).longValue();
                int changed = jdbc.update("update voice_room_participants set status = 'LEFT', left_at = now(), is_muted = false where id = ? and status = 'JOINED'", id);
                if (changed > 0) {
                    try {
                        roomState.onLeft(roomId, userId);
                    } catch (Exception e) {
                        log.debug("Room state not updated after a stale leave: {}", e.getMessage());
                    }
                }
            }
            // Forget rows that are gone or back online
            misses.keySet().retainAll(seen);
        } catch (Exception e) {
            log.debug("Room sweep failed: {}", e.getMessage());
        }
    }
}
