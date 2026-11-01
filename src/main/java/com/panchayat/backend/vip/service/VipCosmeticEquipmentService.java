
        package com.panchayat.backend.vip.service;

import com.panchayat.backend.auth.entity.User;
import com.panchayat.backend.auth.repository.UserRepository;
import com.panchayat.backend.auth.service.VipLevelService;
import com.panchayat.backend.vip.entity.UserEquippedCosmetic;
import com.panchayat.backend.vip.entity.VipCosmetic;
import com.panchayat.backend.vip.repository.UserEquippedCosmeticRepository;
import com.panchayat.backend.vip.repository.UserUnlockedCosmeticRepository;
import com.panchayat.backend.vip.repository.VipCosmeticRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class VipCosmeticEquipmentService {

    private final UserRepository userRepository;
    private final VipCosmeticRepository cosmeticRepository;
    private final UserEquippedCosmeticRepository equippedRepository;
    private final UserUnlockedCosmeticRepository unlockedCosmeticRepository;
    private final VipLevelService vipLevelService;

    public VipCosmeticEquipmentService(
            UserRepository userRepository,
            VipCosmeticRepository cosmeticRepository,
            UserEquippedCosmeticRepository equippedRepository,
            UserUnlockedCosmeticRepository unlockedCosmeticRepository,
            VipLevelService vipLevelService
    ) {
        this.userRepository = userRepository;
        this.cosmeticRepository = cosmeticRepository;
        this.equippedRepository = equippedRepository;
        this.unlockedCosmeticRepository = unlockedCosmeticRepository;
        this.vipLevelService = vipLevelService;
    }

    /**
     * Equip a cosmetic owned by the user.
     * Only one cosmetic per type can be equipped at a time.
     */
    @Transactional
    public EquippedCosmeticResponse equip(
            Long userId,
            Long cosmeticId
    ) {
        User user = findUser(userId);

        if (cosmeticId == null || cosmeticId <= 0) {
            throw new IllegalArgumentException("Invalid cosmetic ID");
        }

        if (!vipLevelService.isVipMembershipActive(user)) {
            throw new IllegalStateException(
                    "An active VIP membership is required"
            );
        }

        VipCosmetic cosmetic = cosmeticRepository.findById(cosmeticId)
                .orElseThrow(() ->
                        new IllegalArgumentException("Cosmetic not found"));

        if (cosmetic.getVipLevel() == null
                || !vipLevelService.canAccessCosmetic(
                user,
                cosmetic.getVipLevel()
        )) {
            throw new IllegalStateException(
                    "This cosmetic is not available for your VIP level"
            );
        }

        // Verify that the cosmetic has been unlocked in this user's inventory.
        boolean isUnlocked = unlockedCosmeticRepository
                .existsByUser_IdAndCosmetic_Id(userId, cosmeticId);

        if (!isUnlocked) {
            throw new IllegalStateException(
                    "This cosmetic is not available in your inventory"
            );
        }

        // Reuse the existing slot for this cosmetic type.
        // This replaces the previously equipped cosmetic of the same type.
        UserEquippedCosmetic equipped = equippedRepository
                .findByUser_IdAndCosmeticType(
                        userId,
                        cosmetic.getCosmeticType()
                )
                .orElseGet(UserEquippedCosmetic::new);

        equipped.setUser(user);
        equipped.setCosmetic(cosmetic);
        equipped.setCosmeticType(cosmetic.getCosmeticType());

        UserEquippedCosmetic saved = equippedRepository.save(equipped);

        return toResponse(saved);
    }

    /**
     * Unequip a specific cosmetic.
     * The cosmetic remains unlocked in the user's inventory.
     */
    @Transactional
    public void unequip(
            Long userId,
            Long cosmeticId
    ) {
        findUser(userId);

        if (cosmeticId == null || cosmeticId <= 0) {
            throw new IllegalArgumentException("Invalid cosmetic ID");
        }

        UserEquippedCosmetic equipped = equippedRepository
                .findByUser_IdAndCosmetic_Id(userId, cosmeticId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "This cosmetic is not currently equipped"
                        ));

        equippedRepository.delete(equipped);
    }

    /**
     * Return currently equipped cosmetics.
     * Equipment records remain in the database when membership expires,
     * but equipped cosmetics are hidden from this response.
     */
    @Transactional(readOnly = true)
    public List<EquippedCosmeticResponse> getEquipped(Long userId) {
        User user = findUser(userId);

        if (!vipLevelService.isVipMembershipActive(user)) {
            return List.of();
        }

        int effectiveVipLevel =
                vipLevelService.calculateEffectiveVipLevel(user);

        return equippedRepository.findAllByUser_Id(userId)
                .stream()
                .filter(e -> e.getCosmetic() != null)
                .filter(e -> e.getCosmetic().getVipLevel() != null)
                .filter(e -> e.getCosmetic().getVipLevel()
                        <= effectiveVipLevel)
                .map(this::toResponse)
                .toList();
    }

    private User findUser(Long userId) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("Invalid user ID");
        }

        return userRepository.findById(userId)
                .orElseThrow(() ->
                        new IllegalArgumentException("User not found"));
    }

    private EquippedCosmeticResponse toResponse(
            UserEquippedCosmetic equipped
    ) {
        VipCosmetic cosmetic = equipped.getCosmetic();

        return new EquippedCosmeticResponse(
                cosmetic.getId(),
                cosmetic.getVipLevel(),
                cosmetic.getCosmeticType(),
                cosmetic.getFormat(),
                cosmetic.getSecureUrl()
        );
    }

    public record EquippedCosmeticResponse(
            Long cosmeticId,
            Integer vipLevel,
            VipCosmetic.CosmeticType type,
            VipCosmetic.CosmeticFormat format,
            String url
    ) {}
}
