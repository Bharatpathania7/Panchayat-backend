package com.panchayat.backend.wallet.repository;

import com.panchayat.backend.wallet.entity.CoinTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CoinTransactionRepository
        extends JpaRepository<CoinTransaction, Long> {

    Page<CoinTransaction> findByUserIdOrderByCreatedAtDesc(
            Long userId,
            Pageable pageable
    );

    Optional<CoinTransaction> findByIdempotencyKey(String idempotencyKey);
}