
        package com.panchayat.backend.wallet.dto;

import com.panchayat.backend.wallet.entity.CoinTransaction;
import java.time.Instant;

public record AdminCoinTransactionResponse(
        Long transactionId,
        Long userId,
        String transactionType,
        long amount,
        long balanceBefore,
        long balanceAfter,
        String reason,
        String referenceId,
        String description,
        Instant createdAt
) {
    public static AdminCoinTransactionResponse from(CoinTransaction transaction) {
        return new AdminCoinTransactionResponse(
                transaction.getId(),
                transaction.getUserId(),
                transaction.getTransactionType().name(),
                transaction.getAmount(),
                transaction.getBalanceBefore(),
                transaction.getBalanceAfter(),
                transaction.getReason(),
                transaction.getReferenceId(),
                transaction.getDescription(),
                transaction.getCreatedAt()
        );
    }
}
