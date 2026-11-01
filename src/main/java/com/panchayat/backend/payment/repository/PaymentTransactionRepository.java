
        package com.panchayat.backend.payment.repository;

import com.panchayat.backend.payment.entity.PaymentTransaction;
import com.panchayat.backend.payment.entity.PaymentTransaction.Status;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface PaymentTransactionRepository
        extends JpaRepository<PaymentTransaction, Long> {

    Optional<PaymentTransaction> findByTxnId(String txnId);

    boolean existsByTxnId(String txnId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT payment
        FROM PaymentTransaction payment
        WHERE payment.txnId = :txnId
    """)
    Optional<PaymentTransaction> findByTxnIdForUpdate(
            @Param("txnId") String txnId
    );

    Optional<PaymentTransaction> findByTxnIdAndStatus(
            String txnId,
            Status status
    );
}
