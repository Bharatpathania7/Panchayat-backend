package com.panchayat.backend.rooms.repository;

import com.panchayat.backend.rooms.entity.RoomCoOwner;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface RoomCoOwnerRepository extends JpaRepository<RoomCoOwner, Long> {

    List<RoomCoOwner> findByRoomId(Long roomId);

    boolean existsByRoomIdAndUserId(Long roomId, Long userId);

    @Transactional
    long deleteByRoomIdAndUserId(Long roomId, Long userId);
}
