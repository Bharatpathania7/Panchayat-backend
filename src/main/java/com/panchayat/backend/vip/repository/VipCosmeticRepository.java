package com.panchayat.backend.vip.repository;

import com.panchayat.backend.vip.entity.VipCosmetic;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface VipCosmeticRepository extends JpaRepository<VipCosmetic, Long> {



    Optional<VipCosmetic> findByVipLevelAndCosmeticTypeAndFormat(
            Integer vipLevel,
            VipCosmetic.CosmeticType cosmeticType,
            VipCosmetic.CosmeticFormat format
    );

    List<VipCosmetic> findAllByOrderByVipLevelAscCosmeticTypeAsc();

    List<VipCosmetic> findAllByVipLevelOrderByCosmeticTypeAsc(Integer vipLevel);

    List<VipCosmetic> findAllByCosmeticTypeOrderByVipLevelAsc(
            VipCosmetic.CosmeticType cosmeticType
    );

    List<VipCosmetic> findAllByFormatOrderByVipLevelAsc(
            VipCosmetic.CosmeticFormat format
    );

    List<VipCosmetic> findAllByVipLevelAndCosmeticTypeOrderByCosmeticTypeAsc(
            Integer vipLevel,
            VipCosmetic.CosmeticType cosmeticType
    );

    List<VipCosmetic> findAllByVipLevelAndCosmeticTypeAndFormat(
            Integer vipLevel,
            VipCosmetic.CosmeticType cosmeticType,
            VipCosmetic.CosmeticFormat format
    );
    List<VipCosmetic> findAllByVipLevelLessThanEqualOrderByVipLevelAscCosmeticTypeAsc(
            Integer vipLevel
    );


}