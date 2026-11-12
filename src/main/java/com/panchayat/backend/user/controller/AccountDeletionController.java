package com.panchayat.backend.user.controller;

import com.panchayat.backend.auth.entity.User;
import com.panchayat.backend.auth.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Deleting your own account (Google Play requires this inside the app). The person's name, email, picture and bio are removed,
 * they are signed out everywhere, and the account can't be used again. Their gifts, messages and room history stay in other
 * people's histories but no longer show who they were. Coins left in the wallet are lost.
 *   DELETE /api/v1/users/me/account  { confirm: "DELETE" }
 */
@RestController
public class AccountDeletionController {

    private static final Logger log = LoggerFactory.getLogger(AccountDeletionController.class);

    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final com.panchayat.backend.admin.users.AccountBanService bans;

    public AccountDeletionController(JdbcTemplate jdbc, UserRepository users, com.panchayat.backend.admin.users.AccountBanService bans) {
        this.jdbc = jdbc;
        this.users = users;
        this.bans = bans;
    }

    public record ConfirmBody(String confirm) {
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handle(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("message", e.getReason() == null ? "That didn't work" : e.getReason()));
    }

    private User me(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null || "anonymousUser".equals(auth.getPrincipal())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return users.findByEmailIgnoreCase(auth.getName()).orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Only user accounts can be deleted here"));
    }

    @DeleteMapping("/api/v1/users/me/account")
    @Transactional
    public Map<String, Object> delete(@RequestBody(required = false) ConfirmBody body, Authentication auth) {
        User u = me(auth);
        if (body == null || !"DELETE".equals(body.confirm() == null ? "" : body.confirm().strip())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Type DELETE to confirm.");
        }
        Long id = u.getId();
        String role = jdbc.queryForObject("select role from users where id = ?", String.class, id);
        if (!"USER".equals(role) || Boolean.TRUE.equals(jdbc.queryForObject("select coalesce(official, false) from users where id = ?", Boolean.class, id))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This account can't be deleted from the app.");
        }
        Integer leads = jdbc.queryForObject("select count(*) from parivar_members m join parivars p on p.id = m.parivar_id where m.user_id = ? and m.role = 'LEADER' and p.status = 'ACTIVE' and (select count(*) from parivar_members x where x.parivar_id = p.id) > 1", Integer.class, id);
        if (leads != null && leads > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You are the Mukhiya of a Parivar with members. Hand over leadership first, then delete your account.");
        }
        jdbc.update("update voice_rooms set status = 'CLOSED' where owner_id = ? and status = 'ACTIVE'", id);
        jdbc.update("update voice_room_participants set status = 'LEFT', left_at = now() where user_id = ? and status = 'JOINED'", id);
        jdbc.update("delete from parivar_members where user_id = ?", id);
        jdbc.update("delete from user_follows where follower_id = ? or followee_id = ?", id, id);
        jdbc.update("delete from refresh_tokens where user_id = ?", id);
        jdbc.update("delete from push_devices where user_id = ?", id);
        jdbc.update("update users set email = ?, full_name = 'Deleted user', avatar_url = null, avatar_public_id = null, bio = null,"
                + " vip_active = false, vip_expires_at = null, ban_permanent = true, ban_reason = 'Account deleted by the user', updated_at = now() where id = ?",
                "deleted-" + id + "@deleted.invalid", id);
        bans.forget(id);
        log.info("Account {} deleted by the user", id);
        return Map.of("deleted", true);
    }
}
