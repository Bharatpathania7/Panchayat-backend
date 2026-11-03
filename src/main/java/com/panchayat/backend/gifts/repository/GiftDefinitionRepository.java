package com.panchayat.backend.gifts.repository;

import com.panchayat.backend.gifts.entity.GiftDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GiftDefinitionRepository extends JpaRepository<GiftDefinition, String> {

    List<GiftDefinition> findAllByOrderBySortOrderAscPriceAsc();

    List<GiftDefinition> findByActiveTrueOrderBySortOrderAscPriceAsc();
}
