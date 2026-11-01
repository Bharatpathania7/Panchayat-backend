
        package com.panchayat.backend.wallet.repository;

import com.panchayat.backend.wallet.entity.UserRedPacketInventory;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRedPacketInventoryRepository
        extends JpaRepository<UserRedPacketInventory, Long> {

    @Query("""
        SELECT inventory
        FROM UserRedPacketInventory inventory
        JOIN FETCH inventory.redPacketProduct product
        WHERE inventory.user.id = :userId
        ORDER BY product.sortOrder ASC
    """)
    List<UserRedPacketInventory> findAllByUserId(
            @Param("userId") Long userId
    );

    Optional<UserRedPacketInventory> findByUser_IdAndRedPacketProduct_Id(
            Long userId,
            Long redPacketProductId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT inventory
        FROM UserRedPacketInventory inventory
        WHERE inventory.user.id = :userId
          AND inventory.redPacketProduct.id = :productId
    """)
    Optional<UserRedPacketInventory> findForUpdate(
            @Param("userId") Long userId,
            @Param("productId") Long productId
    );
}
