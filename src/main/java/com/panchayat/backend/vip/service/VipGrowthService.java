package com.panchayat.backend.vip.service;

import com.panchayat.backend.admin.media.MediaUrls;
import com.panchayat.backend.auth.service.VipLevelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.*;

/**
 * VIP growth ("God Points"): while VIP, every 100 coins spent adds 1 point.
 * When the points reach a new level, the stored VIP level goes up and the
 * person is told on /user/queue/notify:
 *   { type: VIP_LEVEL_UP, from, level, rewards: [{ id, type, format, url, level }] }
 * The rewards are the VIP cosmetics unlocked by the new level(s), ready to wear.
 * Runs inside the caller's transaction (the gift or purchase that earned it).
 */
@Service
public class VipGrowthService {
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private com.panchayat.backend.sarpanch.SarpanchService sarpanch;


    private static final Logger log = LoggerFactory.getLogger(VipGrowthService.class);
    public static final int COINS_PER_POINT = 100;

    private final JdbcTemplate jdbc;
    private final VipLevelService levels;
    private final SimpMessagingTemplate messaging;

    public VipGrowthService(JdbcTemplate jdbc, VipLevelService levels, SimpMessagingTemplate messaging) {
        this.jdbc = jdbc;
        this.levels = levels;
        this.messaging = messaging;
    }

    /** Coins someone spent: adds their growth (only while VIP) and handles a level-up. */
    public void onSpend(Long userId, long coins) {
        if (userId == null || coins < COINS_PER_POINT) return;
        long points = coins / COINS_PER_POINT;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "update users set vip_points = vip_points + ? where id = ? and vip_active and (vip_expires_at is null or vip_expires_at > now())"
                        + " returning vip_points, admin_vip_level, email",
                points, userId);
        if (rows.isEmpty()) return;
        long after = ((Number) rows.get(0).get("vip_points")).longValue();
        Object adminRaw = rows.get(0).get("admin_vip_level");
        int admin = adminRaw == null ? 0 : ((Number) adminRaw).intValue();
        int before = Math.max(levels.calculatePointsBasedLevel(after - points), admin);
        int now = Math.max(levels.calculatePointsBasedLevel(after), admin);
        if (now <= before) return;
        jdbc.update("update users set vip_level = ? where id = ?", now, userId);
        sarpanch.notifySafely(userId, "VIP_LEVEL_UP", Map.of("level", String.valueOf(now)), "vip:" + userId + ":level:" + now);

        // What the new level(s) unlock
        List<Map<String, Object>> rewards = new ArrayList<>();
        jdbc.query("select id, cosmetic_type, format, secure_url, vip_level from vip_cosmetics where vip_level > ? and vip_level <= ? order by vip_level, cosmetic_type",
                rs -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("id", String.valueOf(rs.getLong("id")));
                    r.put("type", rs.getString("cosmetic_type"));
                    r.put("format", rs.getString("format"));
                    r.put("url", MediaUrls.link(rs.getString("secure_url")));
                    r.put("level", rs.getInt("vip_level"));
                    rewards.add(r);
                }, before, now);
        String email = (String) rows.get(0).get("email");
        Map<String, Object> note = new LinkedHashMap<>();
        note.put("type", "VIP_LEVEL_UP");
        note.put("from", before);
        note.put("level", now);
        note.put("points", after);
        note.put("rewards", rewards);
        note.put("at", Instant.now().toString());
        Runnable send = () -> {
            try {
                if (email != null) messaging.convertAndSendToUser(email, "/queue/notify", (Object) note);
            } catch (Exception e) {
                log.debug("VIP level-up notice not sent: {}", e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
        } else {
            send.run();
        }
    }
}
