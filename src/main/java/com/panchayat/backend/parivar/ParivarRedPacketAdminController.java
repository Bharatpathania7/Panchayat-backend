package com.panchayat.backend.parivar;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The admin panel's view of Parivar red packets and bans.
 *   GET  /api/v1/admin/parivar/redpackets?parivarId=   the latest red packets (all Parivars, or one)
 *   GET  /api/v1/admin/parivar/bans?parivarId=         who is banned where
 *   POST /api/v1/admin/parivar/{id}/ban    { userId, reason }   ban (and remove) someone, whatever their role
 *   POST /api/v1/admin/parivar/{id}/unban  { userId }           lift a ban
 */
@RestController
@RequestMapping("/api/v1/admin/parivar")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
public class ParivarRedPacketAdminController {

    private final JdbcTemplate jdbc;
    private final ParivarService core;

    public ParivarRedPacketAdminController(JdbcTemplate jdbc, ParivarService core) {
        this.jdbc = jdbc;
        this.core = core;
    }

    public record BanBody(Long userId, String reason) {
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handle(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("message", e.getReason() == null ? "That didn't work" : e.getReason()));
    }

    @GetMapping("/redpackets")
    public List<Map<String, Object>> redPackets(@RequestParam(required = false) Long parivarId) {
        String where = parivarId == null ? "" : " where r.parivar_id = ?";
        Object[] args = parivarId == null ? new Object[0] : new Object[]{parivarId};
        return jdbc.query("select r.id, r.parivar_id, p.name as parivar, u.full_name as sender, r.source, r.total, r.pieces, r.claimed, r.paid, r.status, r.created_at from parivar_redpackets r"
                        + " join parivars p on p.id = r.parivar_id join users u on u.id = r.sender_id" + where + " order by r.id desc limit 100",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", String.valueOf(rs.getLong("id")));
                    m.put("parivarId", String.valueOf(rs.getLong("parivar_id")));
                    m.put("parivar", rs.getString("parivar"));
                    m.put("sender", rs.getString("sender"));
                    m.put("source", rs.getString("source"));
                    m.put("total", rs.getLong("total"));
                    m.put("pieces", rs.getInt("pieces"));
                    m.put("claimed", rs.getInt("claimed"));
                    m.put("paid", rs.getLong("paid"));
                    m.put("status", rs.getString("status"));
                    m.put("at", rs.getTimestamp("created_at").toInstant().toString());
                    return m;
                }, args);
    }

    @GetMapping("/bans")
    public List<Map<String, Object>> bans(@RequestParam(required = false) Long parivarId) {
        String where = parivarId == null ? "" : " where b.parivar_id = ?";
        Object[] args = parivarId == null ? new Object[0] : new Object[]{parivarId};
        return jdbc.query("select b.parivar_id, p.name as parivar, b.user_id, u.full_name, u.public_id, b.reason, b.created_at, bb.full_name as banned_by from parivar_bans b"
                        + " join parivars p on p.id = b.parivar_id join users u on u.id = b.user_id left join users bb on bb.id = b.banned_by" + where + " order by b.created_at desc limit 200",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("parivarId", String.valueOf(rs.getLong("parivar_id")));
                    m.put("parivar", rs.getString("parivar"));
                    m.put("userId", String.valueOf(rs.getLong("user_id")));
                    m.put("name", rs.getString("full_name"));
                    m.put("publicId", rs.getLong("public_id"));
                    m.put("reason", rs.getString("reason"));
                    m.put("bannedBy", rs.getString("banned_by"));
                    m.put("at", rs.getTimestamp("created_at").toInstant().toString());
                    return m;
                }, args);
    }

    @PostMapping("/{id:[0-9]+}/ban")
    @Transactional
    public Map<String, Object> ban(@PathVariable Long id, @RequestBody BanBody b) {
        if (b == null || b.userId() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose who to ban.");
        core.activeParivar(id);
        String why = b.reason() == null || b.reason().isBlank() ? "Admin" : b.reason().strip().substring(0, Math.min(120, b.reason().strip().length()));
        jdbc.update("insert into parivar_bans (parivar_id, user_id, banned_by, reason) values (?, ?, null, ?) on conflict (parivar_id, user_id) do update set reason = excluded.reason, created_at = now()", id, b.userId(), why);
        jdbc.update("update parivar_applications set status = 'REJECTED', decided_at = now() where parivar_id = ? and user_id = ? and status = 'PENDING'", id, b.userId());
        int removed = jdbc.update("delete from parivar_members where parivar_id = ? and user_id = ? and role <> 'LEADER'", id, b.userId());
        if (removed > 0) core.system(id, "🚫 Ek member ko admin ne Parivar se ban kiya.");
        return Map.of("ok", true);
    }

    @PostMapping("/{id:[0-9]+}/unban")
    @Transactional
    public Map<String, Object> unban(@PathVariable Long id, @RequestBody BanBody b) {
        if (b == null || b.userId() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose who to unban.");
        jdbc.update("delete from parivar_bans where parivar_id = ? and user_id = ?", id, b.userId());
        return Map.of("ok", true);
    }
}
