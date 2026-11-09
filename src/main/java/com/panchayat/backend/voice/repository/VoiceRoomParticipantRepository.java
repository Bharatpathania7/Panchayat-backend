
        package com.panchayat.backend.voice.repository;

import com.panchayat.backend.voice.entity.VoiceRoomParticipant;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VoiceRoomParticipantRepository
        extends JpaRepository<VoiceRoomParticipant, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT p
            FROM VoiceRoomParticipant p
            WHERE p.roomId = :roomId
              AND p.userId = :userId
            """)
    List<VoiceRoomParticipant> findForUpdate(
            @Param("roomId") Long roomId,
            @Param("userId") Long userId
    );

    /** Active rooms with at least one person recorded as in them. */
    @Query("""
            SELECT DISTINCT p.roomId
            FROM VoiceRoomParticipant p, VoiceRoom r
            WHERE r.id = p.roomId
              AND p.status = 'JOINED'
              AND r.status = 'ACTIVE'
            """)
    List<Long> findActiveRoomIdsWithParticipants();

    long countByRoomIdAndStatus(
            Long roomId,
            String status
    );

    Optional<VoiceRoomParticipant> findByRoomIdAndUserIdAndStatus(
            Long roomId,
            Long userId,
            String status
    );

    List<VoiceRoomParticipant> findByRoomIdAndStatus(
            Long roomId,
            String status
    );
    List<VoiceRoomParticipant> findByRoomIdAndStatusOrderByJoinedAtAsc(
            Long roomId,
            String status
    );
}
