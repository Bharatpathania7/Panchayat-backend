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
    private final com.panchayat.backend.user.service.AccountDeletionService deletion;

    public AccountDeletionController(JdbcTemplate jdbc, UserRepository users, com.panchayat.backend.user.service.AccountDeletionService deletion) {
        this.jdbc = jdbc;
        this.users = users;
        this.deletion = deletion;
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
    public Map<String, Object> delete(@RequestBody(required = false) ConfirmBody body, Authentication auth) {
        User u = me(auth);
        if (body == null || !"DELETE".equals(body.confirm() == null ? "" : body.confirm().strip())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Type DELETE to confirm.");
        }
        return deletion.delete(u.getId(), null, null);
    }}
