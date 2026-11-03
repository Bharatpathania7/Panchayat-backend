
        package com.panchayat.backend.voice.repository;

import com.panchayat.backend.voice.entity.VoiceRoom;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VoiceRoomRepository
        extends JpaRepository<VoiceRoom, Long> {

    List<VoiceRoom> findByStatusAndIsPrivateFalseOrderByCreatedAtDesc(
            String status
    );

    Optional<VoiceRoom> findByIdAndOwnerId(
            Long id,
            Long ownerId
    );

    // Locks the room row while checking participant capacity.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM VoiceRoom r WHERE r.id = :id")
    Optional<VoiceRoom> findByIdForUpdate(
            @Param("id") Long id
    );

    /** Adds room points in one statement, so gifts at the same moment never overwrite each other. */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE VoiceRoom r SET r.points = r.points + :points WHERE r.id = :id")
    int addPoints(@Param("id") Long id, @Param("points") long points);
}
