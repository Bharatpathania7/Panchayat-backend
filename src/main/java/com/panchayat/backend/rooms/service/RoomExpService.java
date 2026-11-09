package com.panchayat.backend.rooms.service;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Room EXP, which sets the room level. A room earns:
 *  - from people in it: each person adds 1 EXP a minute (5 minutes = 5 EXP, double for VIP), up to 5,000 a day;
 *  - from gifts: every 10 coins sent is 1 EXP, up to 50,000 a day. Red packets count too, for the 10,000 and 50,000 ones;
 *  - at most 55,000 EXP a day in all.
 * A locked room (private, or with a password) earns nothing. Days are India time.
 */
@Service
public class RoomExpService {

    private static final Logger log = LoggerFactory.getLogger(RoomExpService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "room-exp");
        t.setDaemon(true);
        return t;
    });

    public RoomExpService(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
        ticker.scheduleAtFixedRate(this::voiceMinute, 90, 60, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        ticker.shutdownNow();
    }

    /** Coins sent as a gift in the room (not from a Bag). */
    public void addGift(Long roomId, long coins) {
        if (coins > 0) grant(roomId, false, coins / RoomLevels.COINS_PER_EXP);
    }

    /** A red packet dropped in the room: only the 10,000 and 50,000 ones count. */
    public void addRedPacket(Long roomId, long amount) {
        if (amount == 10_000 || amount == 50_000) grant(roomId, false, amount / RoomLevels.COINS_PER_EXP);
    }

    /** Adds EXP within the daily caps; returns how much was added. */
    long grant(Long roomId, boolean voice, long exp) {
        if (roomId == null || exp <= 0) return 0;
        try {
            Long added = tx.execute(s -> {
                Boolean open = jdbc.query("select status = 'ACTIVE' and not is_private and (password is null or password = '') from voice_rooms where id = ?",
                        rs -> rs.next() ? rs.getBoolean(1) : false, roomId);
                if (!Boolean.TRUE.equals(open)) return 0L;
                LocalDate day = LocalDate.now(IST);
                jdbc.update("insert into room_exp_daily (room_id, day) values (?, ?) on conflict do nothing", roomId, day);
                Map<String, Object> d = jdbc.queryForMap("select voice_exp, gift_exp from room_exp_daily where room_id = ? and day = ? for update", roomId, day);
                long v = ((Number) d.get("voice_exp")).longValue();
                long g = ((Number) d.get("gift_exp")).longValue();
                long room = (voice ? RoomLevels.DAILY_VOICE_CAP - v : RoomLevels.DAILY_GIFT_CAP - g);
                long total = RoomLevels.DAILY_TOTAL_CAP - v - g;
                long ok = Math.max(0, Math.min(exp, Math.min(room, total)));
                if (ok == 0) return 0L;
                jdbc.update(voice ? "update room_exp_daily set voice_exp = voice_exp + ? where room_id = ? and day = ?" : "update room_exp_daily set gift_exp = gift_exp + ? where room_id = ? and day = ?", ok, roomId, day);
                jdbc.update("update voice_rooms set exp = exp + ? where id = ?", ok, roomId);
                return ok;
            });
            return added == null ? 0 : added;
        } catch (Exception e) {
            log.debug("Room EXP not added for room {}: {}", roomId, e.getMessage());
            return 0;
        }
    }

    /** Every minute: each person in an open room adds EXP to it (VIP double). */
    void voiceMinute() {
        try {
            List<Map<String, Object>> rooms = jdbc.queryForList(
                    "select p.room_id, sum(case when u.vip_active and u.vip_expires_at > now() then 2 else 1 end) as e"
                            + " from voice_room_participants p join users u on u.id = p.user_id join voice_rooms r on r.id = p.room_id"
                            + " where p.status = 'JOINED' and r.status = 'ACTIVE' and not r.is_private and (r.password is null or r.password = '')"
                            + " group by p.room_id");
            for (Map<String, Object> r : rooms) {
                grant(((Number) r.get("room_id")).longValue(), true, ((Number) r.get("e")).longValue() * RoomLevels.VOICE_EXP_PER_MINUTE);
            }
        } catch (Exception e) {
            log.debug("Room EXP tick failed: {}", e.getMessage());
        }
    }

    /** The room's level, what it unlocks, and what it earned today. */
    public Map<String, Object> info(Long roomId) {
        Long exp = jdbc.query("select exp from voice_rooms where id = ?", rs -> rs.next() ? rs.getLong(1) : null, roomId);
        if (exp == null) return null;
        int level = RoomLevels.level(exp);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("exp", exp);
        m.put("level", level);
        m.put("levelExp", RoomLevels.EXP[level - 1]);
        m.put("nextExp", RoomLevels.nextExp(exp));
        m.put("members", RoomLevels.MEMBERS[level - 1]);
        m.put("admins", RoomLevels.ADMINS[level - 1]);
        m.put("coOwners", RoomLevels.CO_OWNERS[level - 1]);
        long v = 0, g = 0;
        List<Map<String, Object>> d = jdbc.queryForList("select voice_exp, gift_exp from room_exp_daily where room_id = ? and day = ?", roomId, LocalDate.now(IST));
        if (!d.isEmpty()) {
            v = ((Number) d.get(0).get("voice_exp")).longValue();
            g = ((Number) d.get(0).get("gift_exp")).longValue();
        }
        Map<String, Object> today = new LinkedHashMap<>();
        today.put("voice", v);
        today.put("voiceCap", RoomLevels.DAILY_VOICE_CAP);
        today.put("gift", g);
        today.put("giftCap", RoomLevels.DAILY_GIFT_CAP);
        today.put("total", v + g);
        today.put("totalCap", RoomLevels.DAILY_TOTAL_CAP);
        m.put("today", today);
        Boolean locked = jdbc.query("select is_private or (password is not null and password <> '') from voice_rooms where id = ?", rs -> rs.next() ? rs.getBoolean(1) : false, roomId);
        m.put("locked", Boolean.TRUE.equals(locked));
        return m;
    }
}
