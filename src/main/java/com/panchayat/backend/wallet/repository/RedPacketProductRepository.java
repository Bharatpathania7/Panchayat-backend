
        package com.panchayat.backend.wallet.repository;

import com.panchayat.backend.wallet.entity.RedPacketProduct;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RedPacketProductRepository
        extends JpaRepository<RedPacketProduct, Long> {

    List<RedPacketProduct> findAllByOrderBySortOrderAsc();

    List<RedPacketProduct> findByActiveTrueOrderBySortOrderAsc();

    Optional<RedPacketProduct> findByIdAndActiveTrue(Long id);
}
