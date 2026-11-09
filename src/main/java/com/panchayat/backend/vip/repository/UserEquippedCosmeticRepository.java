
        package com.panchayat.backend.vip.repository;

import com.panchayat.backend.vip.entity.UserEquippedCosmetic;
import com.panchayat.backend.vip.entity.VipCosmetic;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserEquippedCosmeticRepository
        extends JpaRepository<UserEquippedCosmetic, Long> {

    List<UserEquippedCosmetic> findAllByUser_Id(Long userId);

    /** Everything these people wear, with the cosmetics loaded in one query. */
    @org.springframework.data.jpa.repository.Query("""
            SELECT e
            FROM UserEquippedCosmetic e
            JOIN FETCH e.cosmetic
            WHERE e.user.id IN :userIds
            """)
    List<UserEquippedCosmetic> findAllForUsers(
            @org.springframework.data.repository.query.Param("userIds") java.util.Collection<Long> userIds
    );

    Optional<UserEquippedCosmetic>
    findByUser_IdAndCosmeticType(
            Long userId,
            VipCosmetic.CosmeticType cosmeticType
    );

    Optional<UserEquippedCosmetic>
    findByUser_IdAndCosmetic_Id(
            Long userId,
            Long cosmeticId
    );

    void deleteByUser_IdAndCosmetic_Id(
            Long userId,
            Long cosmeticId
    );
}
