
        package com.panchayat.backend.voice.repository;

import com.panchayat.backend.voice.entity.VoiceRoomChatMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface VoiceRoomChatMessageRepository
        extends JpaRepository<VoiceRoomChatMessage, Long> {

    @Query("""
            SELECT m
            FROM VoiceRoomChatMessage m
            WHERE m.roomId = :roomId
              AND m.deletedAt IS NULL
            ORDER BY m.createdAt DESC, m.id DESC
            """)
    List<VoiceRoomChatMessage> findRecentMessages(
            @Param("roomId") Long roomId,
            Pageable pageable
    );

    boolean existsByIdAndRoomId(Long id, Long roomId);
}
