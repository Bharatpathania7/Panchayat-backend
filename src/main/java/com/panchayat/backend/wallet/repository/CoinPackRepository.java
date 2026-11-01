package com.panchayat.backend.wallet.repository;

import com.panchayat.backend.wallet.entity.CoinPack;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CoinPackRepository
        extends JpaRepository<CoinPack, Long> {

    List<CoinPack> findAllByOrderBySortOrderAsc();

    List<CoinPack> findByActiveTrueOrderBySortOrderAsc();

    Optional<CoinPack> findByIdAndActiveTrue(Long id);
}