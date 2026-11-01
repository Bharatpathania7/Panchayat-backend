package com.panchayat.backend.wallet.service;

import com.panchayat.backend.wallet.dto.CreateRedPacketRequest;
import com.panchayat.backend.wallet.dto.UpdateRedPacketRequest;
import com.panchayat.backend.wallet.entity.RedPacketProduct;
import com.panchayat.backend.wallet.repository.RedPacketProductRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@Service
public class RedPacketProductService {

    private final RedPacketProductRepository repository;
    private final WalletImageStorageService imageStorageService;

    public RedPacketProductService(
            RedPacketProductRepository repository,
            WalletImageStorageService imageStorageService
    ) {
        this.repository = repository;
        this.imageStorageService = imageStorageService;
    }

    @Transactional(readOnly = true)
    public List<RedPacketProduct> getAllProducts() {
        return repository.findAllByOrderBySortOrderAsc();
    }

    @Transactional(readOnly = true)
    public List<RedPacketProduct> getActiveProducts() {
        return repository.findByActiveTrueOrderBySortOrderAsc();
    }

    @Transactional
    public RedPacketProduct createProduct(
            CreateRedPacketRequest request
    ) {
        RedPacketProduct product = new RedPacketProduct();

        product.setName(request.name().trim());
        product.setCoinValue(request.coinValue());
        product.setPriceInRupees(request.priceInRupees());
        product.setImageUrl(normalizeImageUrl(request.imageUrl()));

        // Default to active when not provided.
        product.setActive(
                request.active() != null ? request.active() : true
        );

        // Default sort order when not provided.
        product.setSortOrder(
                request.sortOrder() != null ? request.sortOrder() : 0
        );

        return repository.save(product);
    }


    @Transactional
    public RedPacketProduct updateProduct(
            Long id,
            UpdateRedPacketRequest request
    ) {
        RedPacketProduct product = findProduct(id);

        if (request.name() != null) {
            product.setName(request.name().trim());
        }

        if (request.coinValue() != null) {
            product.setCoinValue(request.coinValue());
        }

        if (request.priceInRupees() != null) {
            product.setPriceInRupees(request.priceInRupees());
        }

        // Preserve the existing image if the update omits its URL.
        if (request.imageUrl() != null) {
            product.setImageUrl(normalizeImageUrl(request.imageUrl()));
        }

        if (request.sortOrder() != null) {
            product.setSortOrder(request.sortOrder());
        }

        return repository.save(product);
    }


    @Transactional
    public RedPacketProduct uploadProductImage(
            Long id,
            MultipartFile file
    ) {
        RedPacketProduct product = findProduct(id);

        // Upload the new image to Cloudinary.
        Map<String, String> uploaded =
                imageStorageService.uploadRedPacketImage(file);

        String newImageUrl = uploaded.get("imageUrl");
        String newImagePublicId = uploaded.get("imagePublicId");

        if (newImageUrl == null || newImagePublicId == null) {
            // Clean up incomplete upload details if possible.
            if (newImagePublicId != null) {
                try {
                    imageStorageService.deleteImage(newImagePublicId);
                } catch (RuntimeException ignored) {
                    // Best-effort cleanup.
                }
            }

            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Cloudinary did not return valid image details"
            );
        }

        String oldImagePublicId = product.getImagePublicId();

        product.setImageUrl(newImageUrl);
        product.setImagePublicId(newImagePublicId);

        try {
            // Flush ensures the database update is attempted before deleting
            // the old Cloudinary image.
            RedPacketProduct savedProduct = repository.saveAndFlush(product);

            if (oldImagePublicId != null
                    && !oldImagePublicId.isBlank()
                    && !oldImagePublicId.equals(newImagePublicId)) {
                try {
                    imageStorageService.deleteImage(oldImagePublicId);
                } catch (RuntimeException ignored) {
                    // New image is saved. Old-image cleanup can be retried.
                }
            }

            return savedProduct;

        } catch (RuntimeException exception) {
            // Best-effort cleanup of the new image if saving fails.
            try {
                imageStorageService.deleteImage(newImagePublicId);
            } catch (RuntimeException cleanupException) {
                exception.addSuppressed(cleanupException);
            }

            throw exception;
        }
    }

    @Transactional
    public RedPacketProduct updateStatus(
            Long id,
            boolean active
    ) {
        RedPacketProduct product = findProduct(id);

        product.setActive(active);

        return repository.save(product);
    }

    private RedPacketProduct findProduct(Long id) {
        if (id == null || id <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid red packet product ID"
            );
        }

        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Red packet product not found"
                ));
    }

    private String normalizeImageUrl(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            return null;
        }

        String value = imageUrl.trim();

        if (!value.startsWith("https://")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Image URL must use HTTPS"
            );
        }

        return value;
    }
}