package com.panchayat.backend.tags.repository;

import com.panchayat.backend.tags.entity.UserTag;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserTagRepository extends JpaRepository<UserTag, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM UserTag t WHERE t.pairLow = :low AND t.pairHigh = :high")
    Optional<UserTag> findPairForUpdate(@Param("low") Long low, @Param("high") Long high);

    @Query("SELECT t FROM UserTag t WHERE t.userA = :userId OR t.userB = :userId ORDER BY t.intimacy DESC")
    List<UserTag> findAllFor(@Param("userId") Long userId);

    @Query("SELECT COUNT(t) FROM UserTag t WHERE t.userA = :userId OR t.userB = :userId")
    long countFor(@Param("userId") Long userId);
}
