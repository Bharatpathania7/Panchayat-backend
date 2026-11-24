
        package com.panchayat.backend.wallet.service;

import com.panchayat.backend.auth.repository.UserRepository;
import com.panchayat.backend.wallet.entity.CoinTransaction;
import com.panchayat.backend.wallet.entity.CoinWallet;
import com.panchayat.backend.wallet.repository.CoinTransactionRepository;
import com.panchayat.backend.wallet.repository.CoinWalletRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
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

    /**
     * Locks two people's wallets in a fixed order (lower user id first), inside the caller's transaction. Call it
     * before a debit from one and a credit to the other: two people gifting each other at the same moment then queue
     * on the same lock instead of each holding one wallet and waiting for the other (a deadlock PostgreSQL breaks by
     * failing one of the two payments). Later debit / credit calls in the same transaction re-lock instantly.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void lockInOrder(Long a, Long b) {
        if (a == null || b == null) return;
        Long first = Math.min(a, b), second = Math.max(a, b);
        lockFresh(first);
        if (!second.equals(first)) lockFresh(second);
    }

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * The wallet, locked (SELECT ... FOR UPDATE) AND re-read after the lock is held. A plain locking query is not enough
     * with JPA: when the wallet was already loaded earlier in the same transaction, Hibernate locks the row but hands
     * back the old object with the OLD balance, so two concurrent payments could both start from the same balance
     * and one of them would be lost. refresh() with a lock mode always reloads the row's current values.
     */
    private CoinWallet lockFresh(Long userId) {
        CoinWallet wallet = getOrCreateWallet(userId);
        entityManager.refresh(wallet, LockModeType.PESSIMISTIC_WRITE);
        return wallet;
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

        // Serialize balance changes for this user's wallet: lock the row and re-read it (never a stale balance)
        CoinWallet wallet = lockFresh(userId);

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
