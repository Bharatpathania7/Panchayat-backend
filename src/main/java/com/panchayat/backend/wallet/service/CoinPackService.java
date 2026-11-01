package com.panchayat.backend.wallet.service;

import com.panchayat.backend.wallet.dto.CreateCoinPackRequest;
import com.panchayat.backend.wallet.dto.UpdateCoinPackRequest;
import com.panchayat.backend.wallet.entity.CoinPack;
import com.panchayat.backend.wallet.repository.CoinPackRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@Service
public class CoinPackService {

    private final CoinPackRepository repository;
    private final WalletImageStorageService imageStorageService;

    public CoinPackService(
            CoinPackRepository repository,
            WalletImageStorageService imageStorageService
    ) {
        this.repository = repository;
        this.imageStorageService = imageStorageService;
    }

    // Admin: Get all packs, including inactive packs.
    @Transactional(readOnly = true)
    public List<CoinPack> getAllPacks() {
        return repository.findAllByOrderBySortOrderAsc();
    }

    // User: Get only active packs.
    @Transactional(readOnly = true)
    public List<CoinPack> getActivePacks() {
        return repository.findByActiveTrueOrderBySortOrderAsc();
    }

    // User: Get a specific active pack.
    @Transactional(readOnly = true)
    public CoinPack getActivePackById(Long id) {
        validatePackId(id);

        return repository.findByIdAndActiveTrue(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Active coin pack not found"
                ));
    }

    @Transactional
    public CoinPack createPack(CreateCoinPackRequest request) {
        CoinPack pack = new CoinPack();

        pack.setName(request.name().trim());
        pack.setCoins(request.coins());
        pack.setPriceInRupees(request.priceInRupees());
        pack.setBonusCoins(request.bonusCoins());
        pack.setBonusPercentage(request.bonusPercentage());
        pack.setBadge(normalizeBadge(request.badge()));
        pack.setActive(request.active() != null ? request.active() : true);
        pack.setSortOrder(request.sortOrder() != null ? request.sortOrder() : 0);

        return repository.save(pack);
    }

    @Transactional
    public CoinPack updatePack(Long id, UpdateCoinPackRequest request) {
        CoinPack pack = findPack(id);

        if (request.name() != null) {
            pack.setName(request.name().trim());
        }
        if (request.coins() != null) {
            pack.setCoins(request.coins());
        }
        if (request.priceInRupees() != null) {
            pack.setPriceInRupees(request.priceInRupees());
        }
        if (request.bonusCoins() != null) {
            pack.setBonusCoins(request.bonusCoins());
        }
        if (request.bonusPercentage() != null) {
            pack.setBonusPercentage(request.bonusPercentage());
        }
        if (request.badge() != null) {
            pack.setBadge(normalizeBadge(request.badge()));
        }
        if (request.sortOrder() != null) {
            pack.setSortOrder(request.sortOrder());
        }

        return repository.save(pack);
    }

    @Transactional
    public CoinPack uploadPackImage(Long id, MultipartFile file) {
        CoinPack pack = findPack(id);

        Map<String, String> uploaded =
                imageStorageService.uploadCoinPackImage(file);

        String newImageUrl = uploaded.get("imageUrl");
        String newImagePublicId = uploaded.get("imagePublicId");
        String oldImagePublicId = pack.getImagePublicId();

        pack.setImageUrl(newImageUrl);
        pack.setImagePublicId(newImagePublicId);

        try {
            CoinPack savedPack = repository.saveAndFlush(pack);

            if (oldImagePublicId != null
                    && !oldImagePublicId.isBlank()
                    && !oldImagePublicId.equals(newImagePublicId)) {
                try {
                    imageStorageService.deleteImage(oldImagePublicId);
                } catch (RuntimeException ignored) {
                    // Old image cleanup can be retried.
                }
            }

            return savedPack;

        } catch (RuntimeException exception) {
            try {
                imageStorageService.deleteImage(newImagePublicId);
            } catch (RuntimeException cleanupException) {
                exception.addSuppressed(cleanupException);
            }

            throw exception;
        }
    }

    @Transactional
    public CoinPack updateStatus(Long id, boolean active) {
        CoinPack pack = findPack(id);
        pack.setActive(active);
        return repository.save(pack);
    }

    private CoinPack findPack(Long id) {
        validatePackId(id);

        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Coin pack not found"
                ));
    }

    private void validatePackId(Long id) {
        if (id == null || id <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid coin pack ID"
            );
        }
    }

    private String normalizeBadge(String badge) {
        if (badge == null || badge.isBlank()) {
            return null;
        }

        String normalized = badge.trim().toUpperCase();

        if (!normalized.equals("POPULAR")
                && !normalized.equals("BEST_VALUE")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Badge must be POPULAR, BEST_VALUE, or null"
            );
        }

        return normalized;
    }
}