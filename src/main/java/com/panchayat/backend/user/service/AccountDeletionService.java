package com.panchayat.backend.user.service;

import com.panchayat.backend.admin.users.AccountBanService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Deleting an account, by the person (Settings) or by an admin (Users page): name, email, picture and bio are removed,
 * they are signed out everywhere, and the account can't be used again. Gifts, messages and room history stay in other
 * people's histories but no longer show who they were. Coins left in the wallet are lost.
 */
@Service
public class AccountDeletionService {

    private static final Logger log = LoggerFactory.getLogger(AccountDeletionService.class);

    private final JdbcTemplate jdbc;
    private final AccountBanService bans;

    public AccountDeletionService(JdbcTemplate jdbc, AccountBanService bans) {
        this.jdbc = jdbc;
        this.bans = bans;
    }

    /**
     * Deletes the account. `byAdmin` is the admin's name (null when the person deletes their own account).
     * Staff and official accounts (Sarpanch, Munshi ji, Daroga) are never deleted here.
     */
    @Transactional
    public Map<String, Object> delete(Long id, String byAdmin, String reason) {
        List<Map<String, Object>> found = jdbc.queryForList("select role, coalesce(official, false) as official, official_role, email from users where id = ?", id);
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        Map<String, Object> u = found.get(0);
        if (String.valueOf(u.get("email")).endsWith("@deleted.invalid")) throw new ResponseStatusException(HttpStatus.CONFLICT, "This account is already deleted.");
        if (!"USER".equals(u.get("role")) || Boolean.TRUE.equals(u.get("official")) || u.get("official_role") != null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, byAdmin == null ? "This account can't be deleted from the app." : "Staff and official accounts can't be deleted.");
        }
        Integer leads = jdbc.queryForObject("select count(*) from parivar_members m join parivars p on p.id = m.parivar_id where m.user_id = ? and m.role = 'LEADER' and p.status = 'ACTIVE' and (select count(*) from parivar_members x where x.parivar_id = p.id) > 1", Integer.class, id);
        if (leads != null && leads > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, byAdmin == null
                    ? "You are the Mukhiya of a Parivar with members. Hand over leadership first, then delete your account."
                    : "This person is the Mukhiya of a Parivar with members. Make someone else the Mukhiya first (Families page), then delete.");
        }
        jdbc.update("update voice_rooms set status = 'CLOSED' where owner_id = ? and status = 'ACTIVE'", id);
        jdbc.update("update voice_room_participants set status = 'LEFT', left_at = now() where user_id = ? and status = 'JOINED'", id);
        jdbc.update("delete from parivar_members where user_id = ?", id);
        jdbc.update("delete from user_follows where follower_id = ? or followee_id = ?", id, id);
        jdbc.update("delete from refresh_tokens where user_id = ?", id);
        jdbc.update("delete from push_devices where user_id = ?", id);
        String why = byAdmin == null ? "Account deleted by the user"
                : ("Account deleted by admin " + byAdmin + (reason == null || reason.isBlank() ? "" : ": " + reason.strip()));
        if (why.length() > 300) why = why.substring(0, 300);
        jdbc.update("update users set email = ?, full_name = 'Deleted user', avatar_url = null, avatar_public_id = null, bio = null,"
                        + " vip_active = false, vip_expires_at = null, ban_permanent = true, ban_reason = ?, signup_device = null, signup_fp = null, updated_at = now() where id = ?",
                "deleted-" + id + "@deleted.invalid", why, id);
        bans.forget(id);
        log.info("Account {} deleted {}", id, byAdmin == null ? "by the user" : "by admin " + byAdmin);
        return Map.of("deleted", true);
    }
}
