
        package com.panchayat.backend.admin.shop.repository;

import com.panchayat.backend.admin.shop.entity.ShopProduct;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ShopProductRepository
        extends JpaRepository<ShopProduct, Long> {

    List<ShopProduct> findAllByOrderByDisplayOrderAsc();

    List<ShopProduct> findByCategoryIdOrderByDisplayOrderAsc(Long categoryId);

    List<ShopProduct> findByActiveTrueOrderByDisplayOrderAsc();

    List<ShopProduct> findByCategoryIdAndActiveTrueOrderByDisplayOrderAsc(
            Long categoryId
    );

    boolean existsByCategoryId(Long categoryId);
}
