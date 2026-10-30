package com.panchayat.backend.admin.shop.repository;

import com.panchayat.backend.admin.shop.entity.ShopCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ShopCategoryRepository
        extends JpaRepository<ShopCategory, Long> {

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(
            String name,
            Long id
    );

    List<ShopCategory> findAllByOrderByDisplayOrderAsc();

    // User app: fetch only active categories
    List<ShopCategory> findByActiveTrueOrderByDisplayOrderAsc();
}