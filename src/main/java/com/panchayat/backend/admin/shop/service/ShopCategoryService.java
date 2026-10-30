
        package com.panchayat.backend.admin.shop.service;

import com.panchayat.backend.admin.shop.dto.request.CreateCategoryRequest;
import com.panchayat.backend.admin.shop.dto.request.UpdateCategoryRequest;
import com.panchayat.backend.admin.shop.dto.response.CategoryResponse;
import com.panchayat.backend.admin.shop.entity.ShopCategory;
import com.panchayat.backend.admin.shop.repository.ShopCategoryRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
@Transactional
public class ShopCategoryService {

    private final ShopCategoryRepository categoryRepository;

    public ShopCategoryService(
            ShopCategoryRepository categoryRepository
    ) {
        this.categoryRepository = categoryRepository;
    }

    // Create a new category
    public CategoryResponse createCategory(
            CreateCategoryRequest request
    ) {
        String name = request.getName().trim();

        if (categoryRepository.existsByNameIgnoreCase(name)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Category with this name already exists"
            );
        }

        ShopCategory category = new ShopCategory();

        category.setName(name);
        category.setDescription(request.getDescription());
        category.setImageUrl(request.getImageUrl());
        category.setDisplayOrder(request.getDisplayOrder());
        category.setActive(true);

        ShopCategory savedCategory =
                categoryRepository.save(category);

        return mapToResponse(savedCategory);
    }

    // Get all categories, including inactive categories
    @Transactional(readOnly = true)
    public List<CategoryResponse> getAllCategories() {

        return categoryRepository
                .findAllByOrderByDisplayOrderAsc()
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    // Get a category by ID
    @Transactional(readOnly = true)
    public CategoryResponse getCategoryById(Long id) {

        ShopCategory category = findCategoryById(id);

        return mapToResponse(category);
    }

    // Update an existing category
    public CategoryResponse updateCategory(
            Long id,
            UpdateCategoryRequest request
    ) {
        ShopCategory category = findCategoryById(id);

        String name = request.getName().trim();

        if (categoryRepository
                .existsByNameIgnoreCaseAndIdNot(name, id)) {

            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Another category with this name already exists"
            );
        }

        category.setName(name);
        category.setDescription(request.getDescription());
        category.setImageUrl(request.getImageUrl());
        category.setDisplayOrder(request.getDisplayOrder());

        ShopCategory updatedCategory =
                categoryRepository.save(category);

        return mapToResponse(updatedCategory);
    }

    // Activate or deactivate a category
    public CategoryResponse updateCategoryStatus(
            Long id,
            boolean active
    ) {
        ShopCategory category = findCategoryById(id);

        category.setActive(active);

        ShopCategory updatedCategory =
                categoryRepository.save(category);

        return mapToResponse(updatedCategory);
    }

    // Find category or return 404
    private ShopCategory findCategoryById(Long id) {

        return categoryRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Category not found with ID: " + id
                ));
    }

    // Convert entity into API response DTO
    private CategoryResponse mapToResponse(
            ShopCategory category
    ) {
        return new CategoryResponse(
                category.getId(),
                category.getName(),
                category.getDescription(),
                category.getImageUrl(),
                category.getDisplayOrder(),
                category.getActive(),
                category.getCreatedAt(),
                category.getUpdatedAt()
        );
    }
    // Get active categories for the user app
    @Transactional(readOnly = true)
    public List<CategoryResponse> getActiveCategories() {

        return categoryRepository
                .findByActiveTrueOrderByDisplayOrderAsc()
                .stream()
                .map(this::mapToResponse)
                .toList();
    }
}
