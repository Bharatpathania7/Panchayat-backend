package com.panchayat.backend.gifts.repository;

import com.panchayat.backend.gifts.entity.RoomGiftEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RoomGiftEventRepository extends JpaRepository<RoomGiftEvent, Long> {

    Optional<RoomGiftEvent> findByRequestId(String requestId);

    /** Coins gifted in a room per sender since a moment, highest first: [senderId, coins]. */
    @Query("""
        SELECT e.senderId, SUM(e.coins)
        FROM RoomGiftEvent e
        WHERE e.roomId = :roomId AND e.createdAt >= :since
        GROUP BY e.senderId
        ORDER BY SUM(e.coins) DESC
    """)
    List<Object[]> contributionSince(@Param("roomId") Long roomId, @Param("since") Instant since, Pageable page);
}
