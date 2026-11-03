package com.panchayat.backend.rooms.repository;

import com.panchayat.backend.rooms.entity.RoomAdmin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface RoomAdminRepository extends JpaRepository<RoomAdmin, Long> {

    List<RoomAdmin> findByRoomId(Long roomId);

    boolean existsByRoomIdAndUserId(Long roomId, Long userId);

    @Transactional
    long deleteByRoomIdAndUserId(Long roomId, Long userId);
}
