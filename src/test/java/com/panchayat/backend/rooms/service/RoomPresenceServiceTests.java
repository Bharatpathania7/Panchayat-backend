package com.panchayat.backend.rooms.service;

import com.panchayat.backend.voice.entity.VoiceRoom;
import com.panchayat.backend.voice.entity.VoiceRoomParticipant;
import com.panchayat.backend.voice.repository.VoiceRoomParticipantRepository;
import com.panchayat.backend.voice.repository.VoiceRoomRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import livekit.LivekitModels;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Dropping isn't leaving: the grace period, reconnecting, and LiveKit being down. */
class RoomPresenceServiceTests {

    private static final Long ROOM = 7L;
    private static final Long USER = 42L;

    private final VoiceRoomParticipantRepository participants = mock(VoiceRoomParticipantRepository.class);
    private final VoiceRoomRepository rooms = mock(VoiceRoomRepository.class);
    private final RoomStateService roomState = mock(RoomStateService.class);
    private final LiveKitPermissionService liveKit = mock(LiveKitPermissionService.class);
    private final PlatformTransactionManager txm = mock(PlatformTransactionManager.class);
    private VoiceRoomParticipant row;

    private RoomPresenceService service(long graceSeconds) {
        return new RoomPresenceService(participants, rooms, roomState, liveKit, txm,
                new SimpleMeterRegistry(), graceSeconds, 15, false);
    }

    private static LivekitModels.ParticipantInfo inCall(Long userId, boolean canPublish) {
        return LivekitModels.ParticipantInfo.newBuilder()
                .setIdentity(LiveKitPermissionService.identity(userId))
                .setState(LivekitModels.ParticipantInfo.State.ACTIVE)
                .setPermission(LivekitModels.ParticipantPermission.newBuilder().setCanPublish(canPublish))
                .build();
    }

    @BeforeEach
    void setUp() {
        row = new VoiceRoomParticipant();
        row.setRoomId(ROOM);
        row.setUserId(USER);
        row.setStatus("JOINED");
        when(txm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(participants.findActiveRoomIdsWithParticipants()).thenReturn(List.of(ROOM));
        when(participants.findByRoomIdAndStatus(ROOM, "JOINED")).thenAnswer(i -> "JOINED".equals(row.getStatus()) ? List.of(row) : List.of());
        when(participants.findByRoomIdAndUserIdAndStatus(ROOM, USER, "JOINED")).thenAnswer(i -> "JOINED".equals(row.getStatus()) ? Optional.of(row) : Optional.empty());
        when(rooms.findByIdForUpdate(ROOM)).thenReturn(Optional.of(new VoiceRoom()));
        when(roomState.desiredPublishers(ROOM)).thenReturn(Map.of());
    }

    @Test
    void droppingKeepsYouInTheRoomDuringTheGracePeriod() {
        RoomPresenceService s = service(60);
        when(liveKit.listParticipants(ROOM)).thenReturn(Optional.of(List.of(inCall(USER, false))));
        s.sweep();
        assertEquals("CONNECTED", s.stateOf(ROOM, USER));

        when(liveKit.listParticipants(ROOM)).thenReturn(Optional.of(List.of()));
        s.sweep();
        assertEquals("DISCONNECTED", s.stateOf(ROOM, USER));
        assertEquals("JOINED", row.getStatus());
        verify(roomState, never()).onLeft(any(), any());
    }

    @Test
    void afterTheGracePeriodYouLeftOnce() {
        RoomPresenceService s = service(0);
        when(liveKit.listParticipants(ROOM)).thenReturn(Optional.of(List.of()));
        s.sweep();
        assertEquals("LEFT", row.getStatus());
        s.sweep();
        verify(roomState, times(1)).onLeft(ROOM, USER);
    }

    @Test
    void comingBackInTimeCancelsIt() {
        RoomPresenceService s = service(60);
        when(liveKit.listParticipants(ROOM)).thenReturn(Optional.of(List.of()));
        s.sweep();
        when(liveKit.listParticipants(ROOM)).thenReturn(Optional.of(List.of(inCall(USER, false))));
        s.sweep();
        assertEquals("CONNECTED", s.stateOf(ROOM, USER));
        assertEquals("JOINED", row.getStatus());
    }

    @Test
    void askingForATokenCountsAsConnecting() {
        RoomPresenceService s = service(60);
        s.touch(ROOM, USER);
        when(liveKit.listParticipants(ROOM)).thenReturn(Optional.of(List.of()));
        s.sweep();
        assertEquals("CONNECTING", s.stateOf(ROOM, USER));
        assertEquals("JOINED", row.getStatus());
    }

    @Test
    void liveKitNotAnsweringNeverMarksAnyoneGone() {
        RoomPresenceService s = service(0);
        when(liveKit.listParticipants(ROOM)).thenReturn(Optional.empty());
        s.sweep();
        s.sweep();
        assertEquals("JOINED", row.getStatus());
        verify(roomState, never()).onLeft(any(), any());
    }

    @Test
    void talkingRightsLiveKitDoesntHaveAreCorrected() {
        RoomPresenceService s = service(60);
        when(liveKit.listParticipants(ROOM)).thenReturn(Optional.of(List.of(inCall(USER, false))));
        when(roomState.desiredPublishers(ROOM)).thenReturn(Map.of(USER, true));
        s.sweep();
        verify(liveKit).repair(ROOM, USER, true);
    }

    @Test
    void talkingRightsAlreadyRightAreLeftAlone() {
        RoomPresenceService s = service(60);
        when(liveKit.listParticipants(ROOM)).thenReturn(Optional.of(List.of(inCall(USER, true))));
        when(roomState.desiredPublishers(ROOM)).thenReturn(Map.of(USER, true));
        s.sweep();
        verify(liveKit, never()).repair(any(), any(), anyBoolean());
    }
}
