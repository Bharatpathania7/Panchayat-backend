
        package com.panchayat.backend.vip.repository;

import com.panchayat.backend.vip.entity.UserUnlockedCosmetic;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserUnlockedCosmeticRepository
        extends JpaRepository<UserUnlockedCosmetic, Long> {

    List<UserUnlockedCosmetic> findAllByUser_Id(Long userId);

    Optional<UserUnlockedCosmetic> findByUser_IdAndCosmetic_Id(
            Long userId,
            Long cosmeticId
    );

    boolean existsByUser_IdAndCosmetic_Id(
            Long userId,
            Long cosmeticId
    );
    List<UserUnlockedCosmetic> findAllByUser_IdOrderByCosmetic_VipLevelAscCosmetic_CosmeticTypeAsc(
            Long userId
    );
}
