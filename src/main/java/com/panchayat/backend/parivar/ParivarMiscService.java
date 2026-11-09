package com.panchayat.backend.parivar;

import com.panchayat.backend.voice.dto.CreateVoiceRoomRequest;
import com.panchayat.backend.voice.entity.VoiceRoomType;
import com.panchayat.backend.voice.service.VoiceRoomService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** The Parivar voice room (a normal voice room that only members can enter) and the weekly ranking rewards. */
@Service
public class ParivarMiscService {

    private static final Logger log = LoggerFactory.getLogger(ParivarMiscService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final JdbcTemplate jdbc;
    private final ParivarService core;
    private final VoiceRoomService voice;
    private final TransactionTemplate tx;
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "parivar-weekly");
        t.setDaemon(true);
        return t;
    });

    public ParivarMiscService(JdbcTemplate jdbc, ParivarService core, VoiceRoomService voice, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.core = core;
        this.voice = voice;
        this.tx = tx;
        ticker.scheduleWithFixedDelay(this::weeklyRewards, 180, 600, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        ticker.shutdownNow();
    }

    /* ======================= voice room ======================= */

    /** The Parivar's open voice room (null if there is none), and whether this person may start one. */
    public Map<String, Object> voiceInfo(Long userId, Long parivarId) {
        Map<String, Object> p = core.activeParivar(parivarId);
        if (core.roleOf(parivarId, userId) == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only members can use the Parivar voice room.");
        Map<String, Object> s = core.settings();
        Map<String, Object> m = new LinkedHashMap<>();
        boolean on = Boolean.TRUE.equals(s.get("voice_enabled"));
        int need = ((Number) s.get("voice_min_level")).intValue();
        int level = ((Number) p.get("level")).intValue();
        List<Long> open = jdbc.queryForList("select id from voice_rooms where parivar_id = ? and status = 'ACTIVE' order by id desc limit 1", Long.class, parivarId);
        m.put("enabled", on);
        m.put("minLevel", need);
        m.put("unlocked", level >= need);
        m.put("roomId", open.isEmpty() ? null : String.valueOf(open.get(0)));
        m.put("canStart", on && level >= need && core.can(parivarId, userId, "MANAGE_VOICE"));
        return m;
    }

    @Transactional
    public Map<String, Object> startVoice(Long userId, Long parivarId) {
        Map<String, Object> info = voiceInfo(userId, parivarId);
        if (info.get("roomId") != null) return info;
        if (!Boolean.TRUE.equals(info.get("enabled"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The Parivar voice room is switched off right now.");
        if (!Boolean.TRUE.equals(info.get("unlocked"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "Your Parivar needs level " + info.get("minLevel") + " to open a voice room.");
        core.require(parivarId, userId, "MANAGE_VOICE");
        String name = (String) core.activeParivar(parivarId).get("name");
        var room = voice.createRoom(new CreateVoiceRoomRequest(name + " Parivar", "Only for members of " + name, VoiceRoomType.VOICE, false, 20));
        jdbc.update("update voice_rooms set parivar_id = ? where id = ?", parivarId, room.id());
        core.system(parivarId, "🎙️ The Parivar voice room is open. Come and talk!");
        return voiceInfo(userId, parivarId);
    }

    /** Used by the voice room join: is this person allowed into a Parivar room? */
    public boolean isMember(Long parivarId, Long userId) {
        return core.roleOf(parivarId, userId) != null;
    }

    /* ======================= weekly ranking rewards ======================= */

    /** When a week has ended (India time, Monday), pays the funds the admin set to the Parivars that finished at those places. Runs once per week. */
    void weeklyRewards() {
        try {
            Map<String, Object> s = core.settings();
            if (!Boolean.TRUE.equals(s.get("rank_rewards_enabled"))) return;
            LocalDate thisMonday = LocalDate.now(IST).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            java.sql.Date last = (java.sql.Date) s.get("last_rank_week");
            LocalDate lastPaid = last == null ? null : last.toLocalDate();
            if (lastPaid != null && !lastPaid.isBefore(thisMonday)) return;
            LocalDate from = thisMonday.minusWeeks(1);
            tx.executeWithoutResult(st -> {
                // lock the setting row so two servers can't both pay
                jdbc.queryForList("select id from parivar_settings where id = 1 for update");
                java.sql.Date again = jdbc.query("select last_rank_week from parivar_settings where id = 1", rs -> rs.next() ? rs.getDate(1) : null);
                if (again != null && !again.toLocalDate().isBefore(thisMonday)) return;
                String gift = Boolean.TRUE.equals(s.get("gift_counts_for_ranking")) ? "" : " and kind <> 'GIFT'";
                List<Map<String, Object>> ranked = jdbc.queryForList("select parivar_id, sum(exp) as s, rank() over (order by sum(exp) desc, parivar_id) as r from parivar_activity"
                        + " where created_at >= ? and created_at < ? and parivar_id in (select id from parivars where status = 'ACTIVE')" + gift + " group by parivar_id having sum(exp) > 0",
                        java.sql.Timestamp.from(from.atStartOfDay(IST).toInstant()), java.sql.Timestamp.from(thisMonday.atStartOfDay(IST).toInstant()));
                for (Map<String, Object> r : ranked) {
                    long rank = ((Number) r.get("r")).longValue();
                    List<Long> funds = jdbc.queryForList("select funds from parivar_rank_rewards where active and rank_from <= ? and rank_to >= ? order by funds desc limit 1", Long.class, rank, rank);
                    if (funds.isEmpty() || funds.get(0) <= 0) continue;
                    long pid = ((Number) r.get("parivar_id")).longValue();
                    core.changeFunds(pid, null, funds.get(0), "RANKPRIZE", "rankprize:" + from + ":" + pid);
                    core.activity(pid, 0L, null, "RANKPRIZE", 0, funds.get(0), "won " + funds.get(0) + " funds for finishing #" + rank + " last week", null);
                    core.system(pid, "🏆 Last week's ranking: #" + rank + "! " + funds.get(0) + " funds were added to the treasury.");
                }
                jdbc.update("update parivar_settings set last_rank_week = ? where id = 1", java.sql.Date.valueOf(thisMonday));
            });
        } catch (Exception e) {
            log.warn("Weekly Parivar rewards failed: {}", e.getMessage());
        }
    }
}
