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

    public AdminWalletController(CoinWalletService coinWalletService) {
        this.coinWalletService = coinWalletService;
    }

    @PostMapping("/{userId}/credit")
    public ResponseEntity<Map<String, Object>> creditCoins(
            @PathVariable Long userId,
            @Valid @RequestBody AdminCoinAdjustmentRequest request) {

        CoinTransaction transaction = coinWalletService.credit(
                userId,
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
                userId,
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
                        userId,
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