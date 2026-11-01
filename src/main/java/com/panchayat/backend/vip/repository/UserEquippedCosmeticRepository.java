
        package com.panchayat.backend.vip.repository;

import com.panchayat.backend.vip.entity.UserEquippedCosmetic;
import com.panchayat.backend.vip.entity.VipCosmetic;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserEquippedCosmeticRepository
        extends JpaRepository<UserEquippedCosmetic, Long> {

    List<UserEquippedCosmetic> findAllByUser_Id(Long userId);

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
