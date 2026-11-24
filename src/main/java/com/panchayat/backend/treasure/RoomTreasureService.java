package com.panchayat.backend.treasure;

import com.panchayat.backend.progress.tasks.DailyTaskService;
import com.panchayat.backend.wallet.service.CoinWalletService;
import com.panchayat.backend.wallet.service.WalletPushService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Date;
import java.time.LocalDate;
import java.util.*;

/**
 * Room treasure: boxes open as you spend time in voice rooms today (India time). Following a room, sending a gift and sending a
 * message each take a few minutes (set in the admin panel) off the wait, once a day. VIP-only boxes need an active VIP.
 * Time is counted by the server only (a tick every minute for everyone in an open room); a box can be opened once a day.
 */
@Service
public class RoomTreasureService {

    private static final Logger log = LoggerFactory.getLogger(RoomTreasureService.class);
    private static final Map<String, String> TASK_COLUMN = Map.of("FOLLOW_ROOM", "followed", "SEND_GIFT", "gifted", "SEND_MESSAGE", "messaged");

    private final JdbcTemplate jdbc;
    @Autowired @Lazy private CoinWalletService wallet;
    @Autowired @Lazy private WalletPushService walletPush;

    public RoomTreasureService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> settings() {
        Map<String, Object> s = jdbc.queryForMap("select enabled, bonus_follow, bonus_gift, bonus_message from room_treasure_settings where id = 1");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", Boolean.TRUE.equals(s.get("enabled")));
        out.put("bonusFollow", ((Number) s.get("bonus_follow")).intValue());
        out.put("bonusGift", ((Number) s.get("bonus_gift")).intValue());
        out.put("bonusMessage", ((Number) s.get("bonus_message")).intValue());
        return out;
    }

    /** One more minute in a room for each of these people (called every minute by the voice tick). */
    public void addMinute(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) return;
        try {
            if (!Boolean.TRUE.equals(settings().get("enabled"))) return;
            Long[] ids = userIds.toArray(new Long[0]);
            Date day = Date.valueOf(DailyTaskService.today());
            jdbc.update(con -> {
                var ps = con.prepareStatement("insert into room_treasure_user_day (user_id, day, seconds) select u, ?, 60 from unnest(?) as u"
                        + " on conflict (user_id, day) do update set seconds = room_treasure_user_day.seconds + 60");
                ps.setDate(1, day);
                ps.setArray(2, con.createArrayOf("bigint", ids));
                return ps;
            });
        } catch (Exception e) {
            log.debug("Room treasure minute not saved: {}", e.getMessage());
        }
    }

    /** A task happened (follow a room, send a gift, send a message): its minutes count once today. Never throws. */
    public void mark(Long userId, String kind) {
        String col = kind == null ? null : TASK_COLUMN.get(kind);
        if (userId == null || col == null) return;
        try {
            if (!Boolean.TRUE.equals(settings().get("enabled"))) return;
            jdbc.update("insert into room_treasure_user_day (user_id, day, " + col + ") values (?, ?, true) on conflict (user_id, day) do update set " + col + " = true",
                    userId, Date.valueOf(DailyTaskService.today()));
        } catch (Exception e) {
            log.debug("Room treasure task not saved: {}", e.getMessage());
        }
    }

    private boolean vipActive(Long userId) {
        Boolean v = jdbc.query("select coalesce(vip_active, false) and vip_expires_at is not null and vip_expires_at > now() from users where id = ?",
                rs -> rs.next() ? rs.getBoolean(1) : false, userId);
        return Boolean.TRUE.equals(v);
    }

    private long earnedSeconds(Long userId, Map<String, Object> st) {
        List<Map<String, Object>> rows = jdbc.queryForList("select seconds, followed, gifted, messaged from room_treasure_user_day where user_id = ? and day = ?", userId, Date.valueOf(DailyTaskService.today()));
        if (rows.isEmpty()) return 0;
        Map<String, Object> r = rows.get(0);
        long s = ((Number) r.get("seconds")).longValue();
        if (Boolean.TRUE.equals(r.get("followed"))) s += 60L * (int) st.get("bonusFollow");
        if (Boolean.TRUE.equals(r.get("gifted"))) s += 60L * (int) st.get("bonusGift");
        if (Boolean.TRUE.equals(r.get("messaged"))) s += 60L * (int) st.get("bonusMessage");
        return s;
    }

    public Map<String, Object> info(Long userId) {
        Map<String, Object> st = settings();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", st.get("enabled"));
        if (!Boolean.TRUE.equals(st.get("enabled"))) return out;
        LocalDate day = DailyTaskService.today();
        List<Map<String, Object>> rows = jdbc.queryForList("select followed, gifted, messaged from room_treasure_user_day where user_id = ? and day = ?", userId, Date.valueOf(day));
        Map<String, Object> d = rows.isEmpty() ? Map.of() : rows.get(0);
        long earned = earnedSeconds(userId, st);
        boolean vip = vipActive(userId);
        Set<Long> claimed = new HashSet<>(jdbc.queryForList("select box_id from room_treasure_claims where user_id = ? and day = ?", Long.class, userId, Date.valueOf(day)));
        List<Map<String, Object>> boxes = new ArrayList<>();
        Map<Boolean, Boolean> nextSeen = new HashMap<>();
        for (Map<String, Object> b : jdbc.queryForList("select id, minutes, reward_coins, vip_only from room_treasure_boxes where active order by vip_only, minutes, id")) {
            long id = ((Number) b.get("id")).longValue();
            long need = 60L * ((Number) b.get("minutes")).longValue();
            boolean vipOnly = Boolean.TRUE.equals(b.get("vip_only"));
            String state;
            long left = 0;
            if (claimed.contains(id)) {
                state = "CLAIMED";
            } else if (earned >= need) {
                state = "CLAIMABLE";
            } else if (!nextSeen.getOrDefault(vipOnly, false)) {
                state = "COUNTDOWN";
                left = need - earned;
                nextSeen.put(vipOnly, true);
            } else {
                state = "PREPARING";
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", String.valueOf(id));
            m.put("minutes", ((Number) b.get("minutes")).intValue());
            m.put("coins", ((Number) b.get("reward_coins")).longValue());
            m.put("vipOnly", vipOnly);
            m.put("locked", vipOnly && !vip);
            m.put("state", state);
            m.put("secondsLeft", left);
            boxes.add(m);
        }
        List<Map<String, Object>> tasks = new ArrayList<>();
        addTask(tasks, "FOLLOW_ROOM", "Follow a Voice Room", (int) st.get("bonusFollow"), Boolean.TRUE.equals(d.get("followed")));
        addTask(tasks, "SEND_GIFT", "Send a Gift", (int) st.get("bonusGift"), Boolean.TRUE.equals(d.get("gifted")));
        addTask(tasks, "SEND_MESSAGE", "Send a Message", (int) st.get("bonusMessage"), Boolean.TRUE.equals(d.get("messaged")));
        out.put("earnedSeconds", earned);
        out.put("vip", vip);
        out.put("boxes", boxes);
        out.put("tasks", tasks);
        return out;
    }

    private static void addTask(List<Map<String, Object>> list, String kind, String title, int minutes, boolean done) {
        if (minutes <= 0) return;
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("kind", kind);
        t.put("title", title);
        t.put("minutes", minutes);
        t.put("done", done);
        list.add(t);
    }

    @Transactional
    public Map<String, Object> claim(Long userId, long boxId) {
        Map<String, Object> st = settings();
        if (!Boolean.TRUE.equals(st.get("enabled"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Daily Task isn't available right now");
        List<Map<String, Object>> found = jdbc.queryForList("select minutes, reward_coins, vip_only from room_treasure_boxes where id = ? and active", boxId);
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That box doesn't exist");
        Map<String, Object> b = found.get(0);
        if (Boolean.TRUE.equals(b.get("vip_only")) && !vipActive(userId)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This box is for VIP members");
        if (earnedSeconds(userId, st) < 60L * ((Number) b.get("minutes")).longValue()) throw new ResponseStatusException(HttpStatus.CONFLICT, "This box isn't ready yet");
        long coins = ((Number) b.get("reward_coins")).longValue();
        LocalDate day = DailyTaskService.today();
        int n = jdbc.update("insert into room_treasure_claims (user_id, day, box_id, coins) values (?, ?, ?, ?) on conflict do nothing", userId, Date.valueOf(day), boxId, coins);
        if (n == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "You already opened this box today");
        if (coins > 0) {
            wallet.credit(userId, coins, "ROOM_TREASURE", "treasure:" + day + ":" + boxId, "rt:" + userId + ":" + day + ":" + boxId, "Room treasure box");
            Runnable push = () -> walletPush.pushBalance(userId, "ROOM_TREASURE", coins);
            // After commit, off this request's DB connection (see common.tx.AfterCommit)
        com.panchayat.backend.common.tx.AfterCommit.run(push);
        }
        Map<String, Object> out = new LinkedHashMap<>(info(userId));
        out.put("got", coins);
        return out;
    }
}
