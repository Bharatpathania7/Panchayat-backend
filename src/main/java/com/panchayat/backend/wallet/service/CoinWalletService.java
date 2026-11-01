
        package com.panchayat.backend.wallet.service;

import com.panchayat.backend.auth.repository.UserRepository;
import com.panchayat.backend.wallet.entity.CoinTransaction;
import com.panchayat.backend.wallet.entity.CoinWallet;
import com.panchayat.backend.wallet.repository.CoinTransactionRepository;
import com.panchayat.backend.wallet.repository.CoinWalletRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
public class CoinWalletService {

    private final CoinWalletRepository walletRepository;
    private final CoinTransactionRepository transactionRepository;
    private final UserRepository userRepository;

    public CoinWalletService(
            CoinWalletRepository walletRepository,
            CoinTransactionRepository transactionRepository,
            UserRepository userRepository) {
        this.walletRepository = walletRepository;
        this.transactionRepository = transactionRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public CoinWallet getOrCreateWallet(Long userId) {
        validateUserId(userId);
        validateUserExists(userId);

        return walletRepository.findByUserId(userId)
                .orElseGet(() ->
                        walletRepository.saveAndFlush(new CoinWallet(userId)));
    }

    @Transactional(readOnly = true)
    public long getBalance(Long userId) {
        validateUserId(userId);
        validateUserExists(userId);

        return walletRepository.findByUserId(userId)
                .map(CoinWallet::getBalance)
                .orElse(0L);
    }

    @Transactional
    public CoinTransaction credit(
            Long userId,
            long amount,
            String reason,
            String referenceId,
            String idempotencyKey,
            String description) {

        return applyTransaction(
                userId,
                amount,
                reason,
                referenceId,
                idempotencyKey,
                description,
                CoinTransaction.TransactionType.CREDIT
        );
    }

    @Transactional
    public CoinTransaction debit(
            Long userId,
            long amount,
            String reason,
            String referenceId,
            String idempotencyKey,
            String description) {

        return applyTransaction(
                userId,
                amount,
                reason,
                referenceId,
                idempotencyKey,
                description,
                CoinTransaction.TransactionType.DEBIT
        );
    }

    private CoinTransaction applyTransaction(
            Long userId,
            long amount,
            String reason,
            String referenceId,
            String idempotencyKey,
            String description,
            CoinTransaction.TransactionType type) {

        validateUserId(userId);

        if (amount <= 0) {
            throw new IllegalArgumentException(
                    "Amount must be greater than zero");
        }

        if (reason == null || reason.isBlank() || reason.length() > 40) {
            throw new IllegalArgumentException(
                    "Valid transaction reason is required");
        }

        if (idempotencyKey == null
                || idempotencyKey.isBlank()
                || idempotencyKey.length() > 100) {
            throw new IllegalArgumentException(
                    "Valid idempotency key is required");
        }

        if (referenceId != null && referenceId.length() > 100) {
            throw new IllegalArgumentException(
                    "Reference ID cannot exceed 100 characters");
        }

        if (description != null && description.length() > 500) {
            throw new IllegalArgumentException(
                    "Description cannot exceed 500 characters");
        }

        validateUserExists(userId);

        // Ensure the wallet exists before locking it.
        getOrCreateWallet(userId);

        // Serialize balance changes for this user's wallet.
        CoinWallet wallet = walletRepository.findByUserIdForUpdate(userId)
                .orElseThrow(() ->
                        new IllegalStateException("Wallet could not be locked"));

        // Return the original transaction for an identical retry.
        CoinTransaction existing = transactionRepository
                .findByIdempotencyKey(idempotencyKey)
                .orElse(null);

        if (existing != null) {
            boolean sameRequest =
                    existing.getUserId().equals(userId)
                            && existing.getTransactionType() == type
                            && existing.getAmount() == amount
                            && existing.getReason().equals(reason)
                            && Objects.equals(
                            existing.getReferenceId(), referenceId)
                            && Objects.equals(
                            existing.getDescription(), description);

            if (!sameRequest) {
                throw new IllegalArgumentException(
                        "Idempotency key was already used for a different transaction");
            }

            return existing;
        }

        long balanceBefore = wallet.getBalance();
        final long balanceAfter;

        if (type == CoinTransaction.TransactionType.CREDIT) {
            try {
                balanceAfter = Math.addExact(balanceBefore, amount);
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException(
                        "Coin balance limit exceeded", exception);
            }
        } else {
            if (balanceBefore < amount) {
                throw new IllegalStateException(
                        "Insufficient coin balance");
            }

            balanceAfter = balanceBefore - amount;
        }

        wallet.setBalance(balanceAfter);
        walletRepository.save(wallet);

        CoinTransaction transaction = new CoinTransaction(
                userId,
                type,
                amount,
                balanceBefore,
                balanceAfter,
                reason,
                referenceId,
                idempotencyKey,
                description
        );

        // Save the wallet update and transaction ledger atomically.
        return transactionRepository.saveAndFlush(transaction);
    }

    @Transactional(readOnly = true)
    public Page<CoinTransaction> getTransactionHistory(
            Long userId,
            Pageable pageable) {

        validateUserId(userId);
        validateUserExists(userId);

        if (pageable == null) {
            throw new IllegalArgumentException(
                    "Pagination information is required");
        }

        return transactionRepository
                .findByUserIdOrderByCreatedAtDesc(userId, pageable);
    }

    private void validateUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException(
                    "Valid user ID is required");
        }
    }

    private void validateUserExists(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new IllegalArgumentException("User not found");
        }
    }
}
