package com.panchayat.backend.admin.users;

import com.panchayat.backend.sarpanch.SarpanchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every restriction short of a full ban, in one place: CHAT_BAN (room and private chat both refuse to send)
 * and MOMENT_BAN (can't post a Moment). One admin screen, one table (user_restrictions); applying or lifting
 * one tells the person from Daroga ji. A restriction can be permanent (no expiresAt) or for some days.
 */
@Service
public class UserRestrictionService {

    public static final Set<String> KINDS = Set.of("CHAT_BAN", "MOMENT_BAN");
    private static final Map<String, String> LABEL = Map.of("CHAT_BAN", "send chat messages", "MOMENT_BAN", "post a Moment");

    private final JdbcTemplate jdbc;

    @Autowired @Lazy private SarpanchService sarpanch;

    public UserRestrictionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }

    /** Is this restriction active right now (an expired one no longer counts)? */
    public boolean isRestricted(Long userId, String kind) {
        if (userId == null || !KINDS.contains(kind)) return false;
        Integer n = jdbc.queryForObject(
                "select count(*) from user_restrictions where user_id = ? and kind = ? and (expires_at is null or expires_at > now())",
                Integer.class, userId, kind);
        return n != null && n > 0;
    }

    public List<Map<String, Object>> forUser(Long userId) {
        return jdbc.queryForList(
                "select kind, reason, created_by as \"createdBy\", created_at as \"createdAt\", expires_at as \"expiresAt\","
                        + " (expires_at is null or expires_at > now()) as active"
                        + " from user_restrictions where user_id = ? order by kind", userId);
    }

    @Transactional
    public void setRestriction(Long userId, String kind, boolean on, String reason, Integer days, String admin) {
        if (userId == null) throw bad("Choose who this is for.");
        if (!KINDS.contains(kind)) throw bad("Unknown restriction.");
        if (on) {
            if (days != null && (days < 1 || days > 3650)) throw bad("Days must be between 1 and 3,650, or left empty for permanent.");
            Instant until = days == null ? null : Instant.now().plus(Duration.ofDays(days));
            jdbc.update("insert into user_restrictions (user_id, kind, reason, created_by, created_at, expires_at) values (?, ?, ?, ?, now(), ?)"
                            + " on conflict (user_id, kind) do update set reason = excluded.reason, created_by = excluded.created_by, created_at = now(), expires_at = excluded.expires_at",
                    userId, kind, reason == null || reason.isBlank() ? null : reason.strip(), admin, until == null ? null : Timestamp.from(until));
            String why = reason == null || reason.isBlank() ? "" : " Reason: " + reason.strip() + ".";
            String duration = days == null ? "until further notice" : "for " + days + " day" + (days == 1 ? "" : "s");
            sarpanch.sendSafely(userId, new SarpanchService.Message("A restriction was placed on your account",
                    "You can't " + LABEL.get(kind) + " " + duration + "." + why, null, null, null, "SAFETY"), null);
        } else {
            int n = jdbc.update("delete from user_restrictions where user_id = ? and kind = ?", userId, kind);
            if (n > 0) {
                sarpanch.sendSafely(userId, new SarpanchService.Message("A restriction was lifted",
                        "You can " + LABEL.get(kind) + " again.", null, null, null, "SAFETY"), null);
            }
        }
    }

    public Map<String, Object> summary(Long userId) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String k : KINDS) m.put(k, isRestricted(userId, k));
        return m;
    }
}
