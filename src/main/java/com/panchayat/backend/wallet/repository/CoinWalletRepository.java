package com.panchayat.backend.wallet.repository;

import com.panchayat.backend.wallet.entity.CoinWallet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CoinWalletRepository
        extends JpaRepository<CoinWallet, Long> {

    Optional<CoinWallet> findByUserId(Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT w
        FROM CoinWallet w
        WHERE w.userId = :userId
        """)
    Optional<CoinWallet> findByUserIdForUpdate(
            @Param("userId") Long userId
    );
}