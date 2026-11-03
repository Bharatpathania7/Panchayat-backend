package com.panchayat.backend.rewards.repository;

import com.panchayat.backend.rewards.entity.RewardRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RewardRuleRepository extends JpaRepository<RewardRule, Long> {

    List<RewardRule> findAllByOrderByKindAscCoinThresholdAsc();

    List<RewardRule> findByActiveTrue();
}
