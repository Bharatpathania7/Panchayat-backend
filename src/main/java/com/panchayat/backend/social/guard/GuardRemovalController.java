package com.panchayat.backend.social.guard;

import com.panchayat.backend.admin.media.MediaUrls;
import com.panchayat.backend.auth.repository.UserRepository;
import com.panchayat.backend.sarpanch.SarpanchService;
import com.panchayat.backend.wallet.service.CoinWalletService;
import com.panchayat.backend.wallet.service.WalletPushService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Removing the Guard between two people (each one's guard of the other, both ways).
 *
 *   GET    /api/v1/guard-removal                  { forceCost, incoming: [...], outgoing: [...] }
 *   POST   /api/v1/guard-removal/request/{userId} ask them to remove it; they accept or refuse
 *   POST   /api/v1/guard-removal/{id}/accept      (the person asked) removes the Guard, both ways, for good
 *   POST   /api/v1/guard-removal/{id}/refuse      (the person asked) nothing changes
 *   DELETE /api/v1/guard-removal/{id}             (the person who asked) takes the request back
 *   POST   /api/v1/guard-removal/force/{userId}   removes it right away for forceCost coins (set in the admin panel)
 *
 * "Removed" means: the daily protects and the ring charm between them are deleted, and their gifts stop counting for
 * the Guard (the gifts themselves stay in the gift history and the books). Nothing about the removal is kept: the request
 * row goes too. Only the coin payment of an instant removal stays in the wallet history, like any other spending.
 */
@RestController
@RequestMapping("/api/v1/guard-removal")
public class GuardRemovalController {

    private static final long DEFAULT_FORCE_COST = 1_000_000L;

    private final JdbcTemplate jdbc;
    private final UserRepository users;

    @Autowired @Lazy private CoinWalletService wallet;
    @Autowired @Lazy private WalletPushService walletPush;
    @Autowired @Lazy private SarpanchService sarpanch;

    public GuardRemovalController(JdbcTemplate jdbc, UserRepository users) {
        this.jdbc = jdbc;
        this.users = users;
    }

    /* ---------------- read ---------------- */

    @GetMapping
    public Map<String, Object> state(Authentication auth) {
        return stateFor(me(auth));
    }

    private Map<String, Object> stateFor(Long me) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("forceCost", forceCost());
        out.put("incoming", jdbc.query(
                "select r.id, r.from_id as other, u.full_name, u.avatar_url, r.status, r.created_at from guard_delete_requests r join users u on u.id = r.from_id"
                        + " where r.to_id = ? and r.status = 'PENDING' order by r.created_at desc", (rs, i) -> row(rs), me));
        out.put("outgoing", jdbc.query(
                "select r.id, r.to_id as other, u.full_name, u.avatar_url, r.status, coalesce(r.responded_at, r.created_at) as created_at from guard_delete_requests r join users u on u.id = r.to_id"
                        + " where r.from_id = ? and (r.status = 'PENDING' or r.responded_at > now() - interval '24 hours') order by r.created_at desc", (rs, i) -> row(rs), me));
        return out;
    }

    private static Map<String, Object> row(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(rs.getLong("id")));
        m.put("userId", String.valueOf(rs.getLong("other")));
        m.put("name", rs.getString("full_name"));
        m.put("avatarUrl", MediaUrls.link(rs.getString("avatar_url")));
        m.put("status", rs.getString("status"));
        m.put("at", rs.getTimestamp("created_at").toInstant().toString());
        return m;
    }

    /* ---------------- ask them ---------------- */

    @PostMapping("/request/{userId:[0-9]+}")
    @Transactional
    public Map<String, Object> request(@PathVariable Long userId, Authentication auth) {
        Long me = me(auth);
        lockPair(me, userId);
        requireOther(me, userId);
        requireLink(me, userId);
        Integer waiting = jdbc.queryForObject("select count(*) from guard_delete_requests where status = 'PENDING' and ((from_id = ? and to_id = ?) or (from_id = ? and to_id = ?))",
                Integer.class, me, userId, userId, me);
        if (waiting != null && waiting > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "A request between you two is already waiting for an answer.");
        Integer refused = jdbc.queryForObject("select count(*) from guard_delete_requests where status = 'REFUSED' and responded_at > now() - interval '24 hours' and from_id = ? and to_id = ?",
                Integer.class, me, userId);
        if (refused != null && refused > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "They refused. You can ask again tomorrow, or remove it right away with coins.");
        }
        jdbc.update("delete from guard_delete_requests where status = 'REFUSED' and ((from_id = ? and to_id = ?) or (from_id = ? and to_id = ?))", me, userId, userId, me);
        Long id;
        try {
            id = jdbc.queryForObject("insert into guard_delete_requests (from_id, to_id) values (?, ?) returning id", Long.class, me, userId);
        } catch (DuplicateKeyException twice) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A request between you two is already waiting for an answer.");
        }
        sarpanch.notifySafely(userId, "GUARD_DELETE_REQUEST", Map.of("name", nameOf(me)), "guardreq:" + id);
        return stateFor(me);
    }

    @DeleteMapping("/{id:[0-9]+}")
    @Transactional
    public Map<String, Object> cancel(@PathVariable Long id, Authentication auth) {
        Long me = me(auth);
        jdbc.update("delete from guard_delete_requests where id = ? and from_id = ? and status = 'PENDING'", id, me);
        return stateFor(me);
    }

    /* ---------------- they answer ---------------- */

    @PostMapping("/{id:[0-9]+}/accept")
    @Transactional
    public Map<String, Object> accept(@PathVariable Long id, Authentication auth) {
        Long me = me(auth);
        Long from = pendingFrom(id, me);
        lockPair(me, from);
        wipe(me, from);
        sarpanch.notifySafely(from, "GUARD_DELETE_ACCEPTED", Map.of("name", nameOf(me)), "guardacc:" + id);
        return stateFor(me);
    }

    @PostMapping("/{id:[0-9]+}/refuse")
    @Transactional
    public Map<String, Object> refuse(@PathVariable Long id, Authentication auth) {
        Long me = me(auth);
        Long from = pendingFrom(id, me);
        jdbc.update("update guard_delete_requests set status = 'REFUSED', responded_at = now() where id = ?", id);
        sarpanch.notifySafely(from, "GUARD_DELETE_REFUSED", Map.of("name", nameOf(me)), "guardref:" + id + ":" + System.currentTimeMillis());
        return stateFor(me);
    }

    private Long pendingFrom(Long id, Long me) {
        Long from = jdbc.query("select from_id from guard_delete_requests where id = ? and to_id = ? and status = 'PENDING' for update", rs -> rs.next() ? rs.getLong(1) : null, id, me);
        if (from == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That request is no longer there.");
        return from;
    }

    /* ---------------- remove it right away ---------------- */

    @PostMapping("/force/{userId:[0-9]+}")
    @Transactional
    public Map<String, Object> force(@PathVariable Long userId, Authentication auth) {
        Long me = me(auth);
        lockPair(me, userId);
        requireOther(me, userId);
        requireLink(me, userId);
        long cost = forceCost();
        if (cost > 0) {
            try {
                wallet.debit(me, cost, "GUARD_REMOVE", "guard:" + userId, "guard-remove:" + me + ":" + UUID.randomUUID(), "Removed the Guard with user " + userId);
            } catch (IllegalStateException e) {
                throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "Not enough coins");
            }
            afterCommit(() -> walletPush.pushBalance(me, "GUARD_REMOVE", -cost));
        }
        wipe(me, userId);
        sarpanch.notifySafely(userId, "GUARD_FORCE_DELETED", Map.of("name", nameOf(me)), "guardforce:" + me + ":" + userId + ":" + System.currentTimeMillis());
        return stateFor(me);
    }

    /* ---------------- the Guard itself ---------------- */

    /** Is there any Guard between these two, either way: gifts, rings or daily protects? */
    private boolean hasLink(Long a, Long b) {
        Boolean any = jdbc.queryForObject(
                "select exists (select 1 from room_gift_events where not guard_cleared and ((sender_id = ? and receiver_id = ?) or (sender_id = ? and receiver_id = ?)))"
                        + " or exists (select 1 from cp_ring_charm where (from_id = ? and to_id = ?) or (from_id = ? and to_id = ?))"
                        + " or exists (select 1 from user_protects where (guard_id = ? and user_id = ?) or (guard_id = ? and user_id = ?))",
                Boolean.class, a, b, b, a, a, b, b, a, a, b, b, a);
        return Boolean.TRUE.equals(any);
    }

    private void requireLink(Long a, Long b) {
        if (!hasLink(a, b)) throw new ResponseStatusException(HttpStatus.CONFLICT, "There is no Guard between you two.");
    }

    /** Takes the Guard away both ways and leaves no trace of the request. */
    private void wipe(Long a, Long b) {
        jdbc.update("delete from user_protects where (guard_id = ? and user_id = ?) or (guard_id = ? and user_id = ?)", a, b, b, a);
        jdbc.update("delete from cp_ring_charm where (from_id = ? and to_id = ?) or (from_id = ? and to_id = ?)", a, b, b, a);
        jdbc.update("update room_gift_events set guard_cleared = true where not guard_cleared and ((sender_id = ? and receiver_id = ?) or (sender_id = ? and receiver_id = ?))", a, b, b, a);
        jdbc.update("delete from guard_delete_requests where (from_id = ? and to_id = ?) or (from_id = ? and to_id = ?)", a, b, b, a);
    }

    private long forceCost() {
        Long c = jdbc.query("select force_delete_cost from guard_settings where id = 1", rs -> rs.next() ? rs.getLong(1) : null);
        return c == null ? DEFAULT_FORCE_COST : c;
    }

    /** One action at a time per pair, so two quick taps can't charge twice. */
    private void lockPair(Long a, Long b) {
        long key = Math.min(a, b) * 1_000_003L + Math.max(a, b);
        jdbc.query("select pg_advisory_xact_lock(?)", rs -> null, key);
    }

    private void requireOther(Long me, Long other) {
        if (me.equals(other)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose someone else.");
        Integer exists = jdbc.queryForObject("select count(*) from users where id = ?", Integer.class, other);
        if (exists == null || exists == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown user.");
    }

    private String nameOf(Long id) {
        String n = jdbc.query("select full_name from users where id = ?", rs -> rs.next() ? rs.getString(1) : null, id);
        return n == null || n.isBlank() ? "Someone" : n.strip();
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handle(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("message", e.getReason() == null ? "That didn't work" : e.getReason()));
    }

    private static void afterCommit(Runnable r) {
        // After commit, off this request's DB connection (see common.tx.AfterCommit)
        com.panchayat.backend.common.tx.AfterCommit.run(r);
    }

    private Long me(Authentication auth) {
        if (auth == null || auth.getName() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in again.");
        return users.findByEmailIgnoreCase(auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Sign in with a user account.")).getId();
    }
}
