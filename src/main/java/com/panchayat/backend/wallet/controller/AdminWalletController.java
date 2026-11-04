package com.panchayat.backend.wallet.controller;

import com.panchayat.backend.wallet.dto.AdminCoinAdjustmentRequest;
import com.panchayat.backend.wallet.dto.AdminCoinTransactionResponse;
import com.panchayat.backend.wallet.entity.CoinTransaction;
import com.panchayat.backend.wallet.service.CoinWalletService;

import jakarta.validation.Valid;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/wallets")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
public class AdminWalletController {

    private final CoinWalletService coinWalletService;

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    public AdminWalletController(CoinWalletService coinWalletService) {
        this.coinWalletService = coinWalletService;
    }

    /**
     * The panel can be given either the internal user id or the 7-digit public
     * ID people see in the app (or the official account's short ID).
     * Unknown: 404 { message } instead of a server error.
     */
    private Long resolve(Long id) {
        if (id == null || id <= 0) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Enter a user ID.");
        java.util.List<Long> l = jdbc.queryForList(
                "select id from users where public_id = ? or display_public_id = ? order by id limit 1", Long.class, id, id);
        if (!l.isEmpty()) return l.get(0);
        Integer n = jdbc.queryForObject("select count(*) from users where id = ?", Integer.class, id);
        if (n != null && n > 0) return id;
        throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "No user with ID " + id + ".");
    }

    /** Everything about one wallet: who, balance and totals by kind. */
    @GetMapping("/{userId}")
    public ResponseEntity<Map<String, Object>> wallet(@PathVariable Long userId) {
        Long uid = resolve(userId);
        Map<String, Object> u = jdbc.queryForMap("select id, public_id, display_public_id, full_name, email, avatar_url, vip_active, vip_level, charm_points, created_at from users where id = ?", uid);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("userId", uid);
        out.put("publicId", u.get("display_public_id") != null ? u.get("display_public_id") : u.get("public_id"));
        out.put("name", u.get("full_name"));
        out.put("email", u.get("email"));
        out.put("avatarUrl", com.panchayat.backend.admin.media.MediaUrls.link((String) u.get("avatar_url")));
        out.put("vipActive", u.get("vip_active"));
        out.put("vipLevel", u.get("vip_level"));
        out.put("charmPoints", u.get("charm_points"));
        out.put("joinedAt", u.get("created_at") == null ? null : u.get("created_at").toString());
        out.put("balance", coinWalletService.getBalance(uid));
        java.util.Map<String, Object> totals = new java.util.LinkedHashMap<>();
        jdbc.query("select reason, transaction_type, count(*) as n, coalesce(sum(amount), 0) as coins from coin_transactions where user_id = ? group by reason, transaction_type order by coins desc",
                rs -> {
                    totals.put(rs.getString("reason") + ":" + rs.getString("transaction_type"),
                            Map.of("reason", rs.getString("reason"), "type", rs.getString("transaction_type"), "count", rs.getLong("n"), "coins", rs.getLong("coins")));
                }, uid);
        out.put("byReason", totals.values());
        out.put("totalIn", jdbc.queryForObject("select coalesce(sum(amount), 0) from coin_transactions where user_id = ? and transaction_type = 'CREDIT'", Long.class, uid));
        out.put("totalOut", jdbc.queryForObject("select coalesce(sum(amount), 0) from coin_transactions where user_id = ? and transaction_type = 'DEBIT'", Long.class, uid));
        out.put("rupeesPaid", jdbc.queryForObject("select coalesce(sum(amount), 0) from payment_transactions where user_id = ? and status = 'SUCCESS'", java.math.BigDecimal.class, uid));
        out.put("lastActivityAt", jdbc.queryForObject("select max(created_at)::text from coin_transactions where user_id = ?", String.class, uid));
        return ResponseEntity.ok(out);
    }

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> status(org.springframework.web.server.ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("message", e.getReason() == null ? "That didn't work" : e.getReason()));
    }

    /** Bad input from the panel (amount, reason, key): 400 with the reason, not a server error. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badInput(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage() == null ? "That request isn't valid." : e.getMessage()));
    }

    /** Not enough coins to take: 409. */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> conflict(IllegalStateException e) {
        return ResponseEntity.status(org.springframework.http.HttpStatus.CONFLICT).body(Map.of("message",
                e.getMessage() != null && e.getMessage().toLowerCase().contains("insufficient") ? "They don't have that many coins." : (e.getMessage() == null ? "That can't be done now." : e.getMessage())));
    }

    @PostMapping("/{userId}/credit")
    public ResponseEntity<Map<String, Object>> creditCoins(
            @PathVariable Long userId,
            @Valid @RequestBody AdminCoinAdjustmentRequest request) {

        CoinTransaction transaction = coinWalletService.credit(
                resolve(userId),
                request.amount(),
                request.reason(),
                request.referenceId(),
                request.idempotencyKey(),
                request.description()
        );

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Coins credited successfully",
                "transactionId", transaction.getId(),
                "userId", transaction.getUserId(),
                "transactionType", transaction.getTransactionType().name(),
                "amount", transaction.getAmount(),
                "balanceBefore", transaction.getBalanceBefore(),
                "balanceAfter", transaction.getBalanceAfter()
        ));
    }

    @PostMapping("/{userId}/debit")
    public ResponseEntity<Map<String, Object>> debitCoins(
            @PathVariable Long userId,
            @Valid @RequestBody AdminCoinAdjustmentRequest request) {

        CoinTransaction transaction = coinWalletService.debit(
                resolve(userId),
                request.amount(),
                request.reason(),
                request.referenceId(),
                request.idempotencyKey(),
                request.description()
        );

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Coins debited successfully",
                "transactionId", transaction.getId(),
                "userId", transaction.getUserId(),
                "transactionType", transaction.getTransactionType().name(),
                "amount", transaction.getAmount(),
                "balanceBefore", transaction.getBalanceBefore(),
                "balanceAfter", transaction.getBalanceAfter()
        ));
    }

    @GetMapping("/{userId}/transactions")
    public ResponseEntity<Map<String, Object>> getTransactionHistory(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        if (page < 0) {
            throw new IllegalArgumentException("Page cannot be negative");
        }

        if (size < 1 || size > 100) {
            throw new IllegalArgumentException(
                    "Page size must be between 1 and 100"
            );
        }

        Page<CoinTransaction> transactions =
                coinWalletService.getTransactionHistory(
                        resolve(userId),
                        PageRequest.of(
                                page,
                                size,
                                Sort.by(Sort.Direction.DESC, "createdAt")
                        )
                );

        Page<AdminCoinTransactionResponse> responsePage =
                transactions.map(AdminCoinTransactionResponse::from);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "userId", userId,
                "transactions", responsePage.getContent(),
                "currentPage", responsePage.getNumber(),
                "pageSize", responsePage.getSize(),
                "totalElements", responsePage.getTotalElements(),
                "totalPages", responsePage.getTotalPages(),
                "hasNext", responsePage.hasNext()
        ));
    }
}