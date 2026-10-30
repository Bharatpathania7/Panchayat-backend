
        package com.panchayat.backend.admin.shop.service;

import com.panchayat.backend.admin.shop.dto.request.CreateProductRequest;
import com.panchayat.backend.admin.shop.dto.request.UpdateProductRequest;
import com.panchayat.backend.admin.shop.dto.response.ProductResponse;
import com.panchayat.backend.admin.shop.entity.ShopCategory;
import com.panchayat.backend.admin.shop.entity.ShopProduct;
import com.panchayat.backend.admin.shop.entity.ShopProduct.AssetType;
import com.panchayat.backend.admin.shop.repository.ShopCategoryRepository;
import com.panchayat.backend.admin.shop.repository.ShopProductRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
@Transactional
public class ShopProductService {

    private final ShopProductRepository productRepository;
    private final ShopCategoryRepository categoryRepository;

    public ShopProductService(
            ShopProductRepository productRepository,
            ShopCategoryRepository categoryRepository
    ) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
    }

    // Create product
    public ProductResponse createProduct(CreateProductRequest request) {

        ShopCategory category = findCategoryById(request.getCategoryId());

        if (!Boolean.TRUE.equals(category.getActive())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Cannot add a product to an inactive category"
            );
        }

        ShopProduct product = new ShopProduct();

        product.setCategory(category);
        product.setName(request.getName().trim());
        product.setDescription(request.getDescription());
        product.setPrice(request.getPrice());

        product.setRarity(
                request.getRarity() != null
                        ? request.getRarity()
                        : ShopProduct.Rarity.COMMON
        );

        product.setAssetUrl(request.getAssetUrl());
        product.setAssetType(resolveAssetType(request.getAssetUrl()));
        product.setDisplayOrder(request.getDisplayOrder());
        product.setActive(true);

        ShopProduct savedProduct = productRepository.save(product);

        return mapToResponse(savedProduct);
    }

    // Get all products, including inactive products for admin
    @Transactional(readOnly = true)
    public List<ProductResponse> getAllProducts() {

        return productRepository.findAllByOrderByDisplayOrderAsc()
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    // Get product by ID for admin
    @Transactional(readOnly = true)
    public ProductResponse getProductById(Long id) {

        ShopProduct product = findProductById(id);

        return mapToResponse(product);
    }

    // Get products by category for admin
    @Transactional(readOnly = true)
    public List<ProductResponse> getProductsByCategory(Long categoryId) {

        findCategoryById(categoryId);

        return productRepository
                .findByCategoryIdOrderByDisplayOrderAsc(categoryId)
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    // Update product
    public ProductResponse updateProduct(
            Long id,
            UpdateProductRequest request
    ) {

        ShopProduct product = findProductById(id);
        ShopCategory category = findCategoryById(request.getCategoryId());

        if (!Boolean.TRUE.equals(category.getActive())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Cannot assign a product to an inactive category"
            );
        }

        product.setCategory(category);
        product.setName(request.getName().trim());
        product.setDescription(request.getDescription());
        product.setPrice(request.getPrice());

        product.setRarity(
                request.getRarity() != null
                        ? request.getRarity()
                        : ShopProduct.Rarity.COMMON
        );

        product.setAssetUrl(request.getAssetUrl());
        product.setAssetType(resolveAssetType(request.getAssetUrl()));
        product.setDisplayOrder(request.getDisplayOrder());

        ShopProduct updatedProduct = productRepository.save(product);

        return mapToResponse(updatedProduct);
    }

    // Activate or deactivate product
    public ProductResponse updateProductStatus(
            Long id,
            boolean active
    ) {

        ShopProduct product = findProductById(id);

        product.setActive(active);

        ShopProduct updatedProduct = productRepository.save(product);

        return mapToResponse(updatedProduct);
    }

    // Get active products for the user app
    @Transactional(readOnly = true)
    public List<ProductResponse> getActiveProducts() {

        return productRepository.findByActiveTrueOrderByDisplayOrderAsc()
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    // Get active products by category for the user app
    @Transactional(readOnly = true)
    public List<ProductResponse> getActiveProductsByCategory(Long categoryId) {

        ShopCategory category = findCategoryById(categoryId);

        if (!Boolean.TRUE.equals(category.getActive())) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Active category not found with ID: " + categoryId
            );
        }

        return productRepository
                .findByCategoryIdAndActiveTrueOrderByDisplayOrderAsc(categoryId)
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    // Get a single active product for the user app
    @Transactional(readOnly = true)
    public ProductResponse getActiveProductById(Long id) {

        ShopProduct product = productRepository.findById(id)
                .filter(p -> Boolean.TRUE.equals(p.getActive()))
                .filter(p -> Boolean.TRUE.equals(p.getCategory().getActive()))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Active product not found with ID: " + id
                ));

        return mapToResponse(product);
    }

    // Find product by ID
    private ShopProduct findProductById(Long id) {

        return productRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Product not found with ID: " + id
                ));
    }

    // Find category by ID
    private ShopCategory findCategoryById(Long categoryId) {

        return categoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Category not found with ID: " + categoryId
                ));
    }

    // Determine asset type from the Cloudinary URL
    private AssetType resolveAssetType(String assetUrl) {

        if (assetUrl == null || assetUrl.isBlank()) {
            return null;
        }

        String normalizedUrl = assetUrl.toLowerCase();

        // Remove query parameters before checking the extension
        int queryIndex = normalizedUrl.indexOf('?');

        if (queryIndex >= 0) {
            normalizedUrl = normalizedUrl.substring(0, queryIndex);
        }

        if (normalizedUrl.endsWith(".svga")) {
            return AssetType.SVGA;
        }

        if (normalizedUrl.endsWith(".svg")) {
            return AssetType.SVG;
        }

        if (normalizedUrl.matches(".*\\.(png|jpg|jpeg|webp|gif)$")) {
            return AssetType.IMAGE;
        }

        return null;
    }

    // Map entity to response DTO
    private ProductResponse mapToResponse(ShopProduct product) {

        return new ProductResponse(
                product.getId(),
                product.getCategory().getId(),
                product.getCategory().getName(),
                product.getName(),
                product.getDescription(),
                product.getPrice(),
                product.getRarity(),
                product.getAssetUrl(),
                product.getAssetType(),
                product.getDisplayOrder(),
                product.getActive(),
                product.getCreatedAt(),
                product.getUpdatedAt()
        );
    }
}
