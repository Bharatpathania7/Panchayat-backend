
        package com.panchayat.backend.vip.service;

import com.panchayat.backend.auth.entity.User;
import com.panchayat.backend.auth.repository.UserRepository;
import com.panchayat.backend.auth.service.VipLevelService;
import com.panchayat.backend.vip.entity.UserUnlockedCosmetic;
import com.panchayat.backend.vip.entity.VipCosmetic;
import com.panchayat.backend.vip.repository.UserUnlockedCosmeticRepository;
import com.panchayat.backend.vip.repository.VipCosmeticRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class VipCosmeticUnlockService {

    private final UserRepository userRepository;
    private final VipCosmeticRepository cosmeticRepository;
    private final UserUnlockedCosmeticRepository unlockedRepository;
    private final VipLevelService vipLevelService;

    public VipCosmeticUnlockService(
            UserRepository userRepository,
            VipCosmeticRepository cosmeticRepository,
            UserUnlockedCosmeticRepository unlockedRepository,
            VipLevelService vipLevelService
    ) {
        this.userRepository = userRepository;
        this.cosmeticRepository = cosmeticRepository;
        this.unlockedRepository = unlockedRepository;
        this.vipLevelService = vipLevelService;
    }

    /**
     * Unlock all eligible cosmetics for the user's effective VIP level.
     *
     * Examples:
     * VIP 1 -> cosmetics from levels 1
     * VIP 3 -> cosmetics from levels 1, 2 and 3
     * VIP 5 -> cosmetics from levels 1 through 5
     *
     * Existing unlock records are preserved.
     *
     * @return number of newly unlocked cosmetics
     */
    @Transactional
    public int unlockEligibleCosmetics(Long userId) {

        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("Invalid user ID");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() ->
                        new IllegalArgumentException("User not found"));

        // Do not create new unlocks for inactive VIP memberships.
        // Existing inventory records remain in the database.
        if (!vipLevelService.isVipMembershipActive(user)) {
            return 0;
        }

        int effectiveVipLevel =
                vipLevelService.calculateEffectiveVipLevel(user);

        if (effectiveVipLevel < 1 || effectiveVipLevel > 11) {
            return 0;
        }

        List<VipCosmetic> eligibleCosmetics =
                cosmeticRepository
                        .findAllByVipLevelLessThanEqualOrderByVipLevelAscCosmeticTypeAsc(
                                effectiveVipLevel
                        );

        int unlockedCount = 0;

        for (VipCosmetic cosmetic : eligibleCosmetics) {

            if (cosmetic == null || cosmetic.getId() == null) {
                continue;
            }

            if (cosmetic.getVipLevel() == null
                    || cosmetic.getVipLevel() < 1
                    || cosmetic.getVipLevel() > effectiveVipLevel) {
                continue;
            }

            boolean alreadyUnlocked =
                    unlockedRepository.existsByUser_IdAndCosmetic_Id(
                            userId,
                            cosmetic.getId()
                    );

            if (alreadyUnlocked) {
                continue;
            }

            UserUnlockedCosmetic unlocked =
                    new UserUnlockedCosmetic();

            unlocked.setUser(user);
            unlocked.setCosmetic(cosmetic);

            unlockedRepository.save(unlocked);
            unlockedCount++;
        }

        return unlockedCount;
    }
}
