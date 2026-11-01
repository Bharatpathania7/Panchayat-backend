
        package com.panchayat.backend.vip.service;

import com.panchayat.backend.auth.entity.User;
import com.panchayat.backend.auth.repository.UserRepository;
import com.panchayat.backend.auth.service.VipLevelService;
import com.panchayat.backend.vip.dto.VipCosmeticInventoryResponse;
import com.panchayat.backend.vip.entity.UserEquippedCosmetic;
import com.panchayat.backend.vip.entity.UserUnlockedCosmetic;
import com.panchayat.backend.vip.repository.UserEquippedCosmeticRepository;
import com.panchayat.backend.vip.repository.UserUnlockedCosmeticRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class VipCosmeticInventoryService {

    private final UserRepository userRepository;
    private final UserUnlockedCosmeticRepository unlockedRepository;
    private final UserEquippedCosmeticRepository equippedRepository;
    private final VipCosmeticUnlockService unlockService;
    private final VipLevelService vipLevelService;

    public VipCosmeticInventoryService(
            UserRepository userRepository,
            UserUnlockedCosmeticRepository unlockedRepository,
            UserEquippedCosmeticRepository equippedRepository,
            VipCosmeticUnlockService unlockService,
            VipLevelService vipLevelService
    ) {
        this.userRepository = userRepository;
        this.unlockedRepository = unlockedRepository;
        this.equippedRepository = equippedRepository;
        this.unlockService = unlockService;
        this.vipLevelService = vipLevelService;
    }

    @Transactional
    public List<VipCosmeticInventoryResponse> getInventory(
            String authenticatedEmail
    ) {
        User user = userRepository.findByEmail(authenticatedEmail)
                .orElseThrow(() ->
                        new IllegalArgumentException("User not found"));

        if (!vipLevelService.isVipMembershipActive(user)) {
            return List.of();
        }

        // Automatically unlock all eligible cosmetics.
        unlockService.unlockEligibleCosmetics(user.getId());

        List<UserEquippedCosmetic> equippedCosmetics =
                equippedRepository.findAllByUser_Id(user.getId());

        List<Long> equippedIds = equippedCosmetics.stream()
                .filter(e -> e.getCosmetic() != null)
                .map(e -> e.getCosmetic().getId())
                .toList();

        return unlockedRepository
                .findAllByUser_IdOrderByCosmetic_VipLevelAscCosmetic_CosmeticTypeAsc(
                        user.getId()
                )
                .stream()
                .filter(unlocked -> unlocked.getCosmetic() != null)
                .map(unlocked -> {
                    var cosmetic = unlocked.getCosmetic();

                    return new VipCosmeticInventoryResponse(
                            cosmetic.getId(),
                            cosmetic.getVipLevel(),
                            cosmetic.getCosmeticType(),
                            cosmetic.getFormat(),
                            cosmetic.getSecureUrl(),
                            cosmetic.getOriginalFilename(),
                            unlocked.getUnlockedAt(),
                            equippedIds.contains(cosmetic.getId())
                    );
                })
                .toList();
    }
}
