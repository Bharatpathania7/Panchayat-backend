package com.panchayat.backend.rewards.repository;

import com.panchayat.backend.rewards.entity.RewardRuleItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface RewardRuleItemRepository extends JpaRepository<RewardRuleItem, Long> {

    @Query("select distinct i.itemType from RewardRuleItem i order by i.itemType")
    List<String> usedTypes();
}
