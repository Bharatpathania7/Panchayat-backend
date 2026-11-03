package com.panchayat.backend.rooms.repository;

import com.panchayat.backend.rooms.entity.RoomBan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface RoomBanRepository extends JpaRepository<RoomBan, Long> {

    Optional<RoomBan> findByRoomIdAndUserId(Long roomId, Long userId);

    List<RoomBan> findByRoomIdOrderByCreatedAtDesc(Long roomId);

    @Transactional
    long deleteByRoomIdAndUserId(Long roomId, Long userId);
}
