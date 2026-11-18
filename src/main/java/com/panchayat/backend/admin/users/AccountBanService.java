package com.panchayat.backend.admin.users;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.panchayat.backend.sarpanch.SarpanchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Account bans. A ban is permanent or lasts some days (a suspension). A banned
 * person can't sign in, refresh a session, call the API or connect to live
 * updates; the person is told by a message from Sarpanch. The check is cached
 * for a few seconds so it costs nothing per request, and cleared at once when
 * staff ban or unban.
 */
@Service
public class AccountBanService {

    private static final Logger log = LoggerFactory.getLogger(AccountBanService.class);

    public record Ban(boolean permanent, Instant until, String reason) {
        public String message() {
            String why = reason == null || reason.isBlank() ? "" : " Reason: " + reason + ".";
            return permanent
                    ? "Your Panchayat account has been banned." + why
                    : "Your Panchayat account is suspended until " + SarpanchService.formatDate(until) + "." + why;
        }
    }

    private final JdbcTemplate jdbc;
    private final SarpanchService sarpanch;
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private SimpMessagingTemplate messaging;
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private com.panchayat.backend.rooms.service.RoomStateService roomState;
    private final Cache<Long, Optional<Ban>> cache = Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(10)).maximumSize(50_000).build();

    public AccountBanService(JdbcTemplate jdbc, @org.springframework.context.annotation.Lazy SarpanchService sarpanch) {
        this.jdbc = jdbc;
        this.sarpanch = sarpanch;
    }

    /** The ban on this account right now, or null. If the database can't be asked, the person is let through. */
    /** The ban check is cached for a few seconds; this makes a change (an account deleted) count straight away. */
    public void forget(Long userId) {
        cache.invalidate(userId);
    }

    public Ban activeBan(Long userId) {
        if (userId == null) return null;
        try {
            return cache.get(userId, id -> Optional.ofNullable(load(id))).orElse(null);
        } catch (Exception e) {
            log.debug("Ban check skipped: {}", e.getMessage());
            return null;
        }
    }

    private Ban load(Long id) {
        return jdbc.query("select ban_permanent, banned_until, ban_reason from users where id = ?", rs -> {
            if (!rs.next()) return null;
            boolean permanent = rs.getBoolean(1);
            Timestamp until = rs.getTimestamp(2);
            if (permanent) return new Ban(true, null, rs.getString(3));
            if (until != null && until.toInstant().isAfter(Instant.now())) return new Ban(false, until.toInstant(), rs.getString(3));
            return null;
        }, id);
    }

    /** Ban for `days` (1 to 3650), or for good when days is null. */
    @Transactional
    public Ban ban(Long userId, Integer days, String reason, String admin) {
        if (days != null && (days < 1 || days > 3650)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Days must be 1 to 3650, or leave it empty for a permanent ban.");
        Integer exists = jdbc.queryForObject("select count(*) from users where id = ?", Integer.class, userId);
        if (exists == null || exists == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        if (sarpanch.isOfficialAccount(userId)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Official accounts can't be banned.");
        String why = reason == null || reason.isBlank() ? null : reason.strip().substring(0, Math.min(300, reason.strip().length()));
        Instant until = days == null ? null : Instant.now().plus(Duration.ofDays(days));
        jdbc.update("update users set ban_permanent = ?, banned_until = ?, ban_reason = ?, banned_at = now() where id = ?",
                days == null, until == null ? null : Timestamp.from(until), why, userId);
        jdbc.update("insert into account_ban_log (user_id, action, days, reason, admin_name) values (?, ?, ?, ?, ?)",
                userId, days == null ? "BAN" : "SUSPEND", days, why, admin);
        // End every session and take them out of rooms
        jdbc.update("update refresh_tokens set revoked_at = timestamptz '2000-01-01 00:00:00+00' where user_id = ? and revoked_at is null", userId);
        jdbc.update("update voice_room_participants set status = 'LEFT' where user_id = ? and status = 'JOINED'", userId);
        // Their own rooms close (the Newbie Welcome Rooms stay: the admin panel runs those)
        List<Long> owned = jdbc.queryForList("select id from voice_rooms where owner_id = ? and status = 'ACTIVE' and not newbie", Long.class, userId);
        for (Long rid : owned) {
            jdbc.update("update voice_rooms set status = 'CLOSED', updated_at = now() where id = ?", rid);
            jdbc.update("update voice_room_participants set status = 'LEFT', left_at = now(), is_muted = false where room_id = ? and status = 'JOINED'", rid);
        }
        cache.invalidate(userId);
        Ban b = new Ban(days == null, until, why);
        Map<String, Object> who = jdbc.query("select full_name, public_id, avatar_url, email from users where id = ?", rs -> {
            if (!rs.next()) return null;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", rs.getString(1));
            m.put("publicId", rs.getString(2));
            m.put("avatarUrl", rs.getString(3));
            m.put("email", rs.getString(4));
            return m;
        }, userId);
        afterCommit(() -> {
            // Everyone in the room sees it close
            for (Long rid : owned) {
                try {
                    roomState.onRoomClosed(rid);
                } catch (Exception e) {
                    log.warn("Room {} close notice not sent: {}", rid, e.getMessage());
                }
            }
            if (who == null) return;
            try {
                // The person sees it at once if the app is open
                Map<String, Object> mine = new LinkedHashMap<>();
                mine.put("type", "BANNED");
                mine.put("permanent", b.permanent());
                mine.put("until", b.until() == null ? null : b.until().toString());
                mine.put("message", b.message());
                messaging.convertAndSendToUser((String) who.get("email"), "/queue/account", (Object) mine);
            } catch (Exception e) {
                log.warn("Ban broadcast not sent for {}: {}", userId, e.getMessage());
            }
        });
        try {
            sarpanch.send(userId, new SarpanchService.Message(days == null ? "Account banned" : "Account suspended", b.message(), null, null, null, "SAFETY"), null);
        } catch (Exception e) {
            log.warn("Ban notice not sent to {}: {}", userId, e.getMessage());
        }
        return b;
    }

    @Transactional
    public void unban(Long userId, String admin) {
        int n = jdbc.update("update users set ban_permanent = false, banned_until = null, ban_reason = null where id = ?", userId);
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        jdbc.update("insert into account_ban_log (user_id, action, admin_name) values (?, 'UNBAN', ?)", userId, admin);
        cache.invalidate(userId);
        try {
            sarpanch.send(userId, new SarpanchService.Message("Account restored", "Your Panchayat account is active again. Welcome back!", null, null, null, "SAFETY"), null);
        } catch (Exception e) {
            log.warn("Unban notice not sent to {}: {}", userId, e.getMessage());
        }
    }

    private static void afterCommit(Runnable r) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    r.run();
                }
            });
        } else {
            r.run();
        }
    }

    /** Thrown where a banned person tries to sign in or refresh. */
    public static ResponseStatusException refusal(Ban b) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, b.message());
    }
}
