package com.panchayat.backend.rooms.repository;

import com.panchayat.backend.rooms.entity.RoomFollower;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface RoomFollowerRepository extends JpaRepository<RoomFollower, Long> {

    boolean existsByRoomIdAndUserId(Long roomId, Long userId);

    long countByRoomId(Long roomId);

    List<RoomFollower> findByUserId(Long userId);

    @Transactional
    long deleteByRoomIdAndUserId(Long roomId, Long userId);
}
