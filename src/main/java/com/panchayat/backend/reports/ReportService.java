package com.panchayat.backend.reports;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reports from people about a user, a chat message or a room. Anyone signed in can report; an admin reviews them
 * (Admin > Reports), acts on the person (mute / ban, in the users page) and resolves or dismisses the report.
 */
@Service
public class ReportService {

    public static final Set<String> TYPES = Set.of("USER", "MESSAGE", "ROOM");
    public static final Set<String> REASONS = Set.of("SPAM", "ABUSE", "HARASSMENT", "NUDITY", "HATE", "SCAM", "UNDERAGE", "OTHER");
    private static final int DAILY_LIMIT = 30;

    private final JdbcTemplate jdbc;

    public ReportService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }

    private static String cut(String s, int max) {
        if (s == null) return null;
        String t = s.strip();
        if (t.isEmpty()) return null;
        return t.length() > max ? t.substring(0, max) : t;
    }

    @Transactional
    public Map<String, Object> report(Long reporterId, String type, Long userId, String ref, String reason, String note, String snippet) {
        String t = type == null ? "" : type.toUpperCase();
        String r = reason == null ? "" : reason.toUpperCase();
        if (!TYPES.contains(t)) throw bad("Choose what you are reporting.");
        if (!REASONS.contains(r)) throw bad("Choose a reason.");
        if (userId != null && userId.equals(reporterId)) throw bad("You can't report yourself.");
        if (userId != null && jdbc.queryForObject("select count(*) from users where id = ?", Long.class, userId) == 0) throw bad("That person doesn't exist.");
        if (userId == null && ref == null) throw bad("Say who or what you are reporting.");
        Long today = jdbc.queryForObject("select count(*) from user_reports where reporter_id = ? and created_at > now() - interval '1 day'", Long.class, reporterId);
        if (today != null && today >= DAILY_LIMIT) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "You have sent a lot of reports today. Try again tomorrow.");
        String key = cut(ref, 80);
        // The same person reporting the same thing again while it is still open: one report is enough
        Long dup = jdbc.queryForObject("select count(*) from user_reports where reporter_id = ? and target_type = ? and status = 'OPEN'"
                + " and coalesce(target_user_id, 0) = coalesce(?, 0) and coalesce(target_ref, '') = coalesce(?, '')", Long.class, reporterId, t, userId, key);
        if (dup != null && dup > 0) return Map.of("sent", true, "duplicate", true);
        jdbc.update("insert into user_reports (reporter_id, target_type, target_user_id, target_ref, snippet, reason, note) values (?, ?, ?, ?, ?, ?, ?)",
                reporterId, t, userId, key, cut(snippet, 300), r, cut(note, 500));
        return Map.of("sent", true);
    }

    /* ---------------- admin ---------------- */

    public List<Map<String, Object>> list(String status) {
        String st = status == null || status.isBlank() ? null : status.toUpperCase();
        return jdbc.queryForList("select r.id, r.target_type as \"targetType\", r.target_ref as \"targetRef\", r.snippet, r.reason, r.note, r.status, r.handled_by as \"handledBy\", r.handled_at as \"handledAt\","
                + " r.created_at as \"createdAt\", r.reporter_id as \"reporterId\", rp.full_name as \"reporterName\", rp.public_id as \"reporterPublicId\","
                + " r.target_user_id as \"targetUserId\", tu.full_name as \"targetName\", tu.public_id as \"targetPublicId\","
                + " (select count(*) from user_reports x where x.target_user_id = r.target_user_id and r.target_user_id is not null) as \"totalAgainst\""
                + " from user_reports r join users rp on rp.id = r.reporter_id left join users tu on tu.id = r.target_user_id"
                + (st == null ? "" : " where r.status = '" + (Set.of("OPEN", "RESOLVED", "DISMISSED").contains(st) ? st : "OPEN") + "'")
                + " order by r.created_at desc limit 300");
    }

    public long openCount() {
        Long n = jdbc.queryForObject("select count(*) from user_reports where status = 'OPEN'", Long.class);
        return n == null ? 0 : n;
    }

    @Transactional
    public Map<String, Object> setStatus(Long id, String status, String admin) {
        String st = status == null ? "" : status.toUpperCase();
        if (!Set.of("OPEN", "RESOLVED", "DISMISSED").contains(st)) throw bad("Choose OPEN, RESOLVED or DISMISSED.");
        int n = "OPEN".equals(st)
                ? jdbc.update("update user_reports set status = 'OPEN', handled_by = null, handled_at = null where id = ?", id)
                : jdbc.update("update user_reports set status = ?, handled_by = ?, handled_at = now() where id = ?", st, admin, id);
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Report not found");
        return Map.of("id", id, "status", st);
    }
}
