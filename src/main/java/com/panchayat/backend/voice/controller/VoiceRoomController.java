
        package com.panchayat.backend.voice.controller;

import com.panchayat.backend.voice.dto.CreateVoiceRoomRequest;
import com.panchayat.backend.voice.dto.JoinVoiceRoomResponse;
import com.panchayat.backend.voice.dto.MuteParticipantRequest;
import com.panchayat.backend.voice.dto.VoiceRoomParticipantResponse;
import com.panchayat.backend.voice.dto.VoiceRoomResponse;
import com.panchayat.backend.voice.service.VoiceRoomService;

import jakarta.validation.Valid;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/voice-rooms")
public class VoiceRoomController {

    private final VoiceRoomService voiceRoomService;

    public VoiceRoomController(VoiceRoomService voiceRoomService) {
        this.voiceRoomService = voiceRoomService;
    }

    /**
     * Create a new voice room.
     */
    @PostMapping
    public ResponseEntity<VoiceRoomResponse> createRoom(
            @Valid @RequestBody CreateVoiceRoomRequest request) {

        VoiceRoomResponse response =
                voiceRoomService.createRoom(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    /**
     * Get all active public voice rooms.
     */
    @GetMapping
    public ResponseEntity<List<VoiceRoomResponse>> getPublicRooms() {

        return ResponseEntity.ok(
                voiceRoomService.getPublicRooms()
        );
    }

    /**
     * Get a voice room by ID.
     */
    @GetMapping("/{id}")
    public ResponseEntity<VoiceRoomResponse> getRoomById(
            @PathVariable Long id) {

        return ResponseEntity.ok(
                voiceRoomService.getRoomById(id)
        );
    }

    /**
     * Join a voice room and generate a LiveKit access token.
     */
    @PostMapping("/{id}/join")
    public ResponseEntity<JoinVoiceRoomResponse> joinRoom(
            @PathVariable Long id) {

        JoinVoiceRoomResponse response =
                voiceRoomService.joinRoom(id);

        return ResponseEntity.ok(response);
    }

    /**
     * Leave a voice room.
     */
    @PostMapping("/{id}/leave")
    public ResponseEntity<Void> leaveRoom(
            @PathVariable Long id) {

        voiceRoomService.leaveRoom(id);

        return ResponseEntity.noContent().build();
    }

    /**
     * Close a voice room.
     * Only the room owner should be allowed to close it.
     */
    @PostMapping("/{id}/close")
    public ResponseEntity<Void> closeRoom(
            @PathVariable Long id) {

        voiceRoomService.closeRoom(id);

        return ResponseEntity.noContent().build();
    }

    /**
     * Get active participants in a voice room.
     */
    @GetMapping("/{id}/participants")
    public ResponseEntity<List<VoiceRoomParticipantResponse>> getParticipants(
            @PathVariable Long id) {

        return ResponseEntity.ok(
                voiceRoomService.getParticipants(id)
        );
    }

    /**
     * Get the number of active participants in a voice room.
     */
    @GetMapping("/{id}/participants/count")
    public ResponseEntity<Long> getParticipantCount(
            @PathVariable Long id) {

        return ResponseEntity.ok(
                voiceRoomService.getParticipantCount(id)
        );
    }

    /**
     * Mute or unmute an active participant.
     * Only the room owner or moderator should be allowed.
     */
    @PatchMapping("/{id}/mute")
    public ResponseEntity<Void> setParticipantMuted(
            @PathVariable Long id,
            @Valid @RequestBody MuteParticipantRequest request) {

        voiceRoomService.setParticipantMuted(
                id,
                request.userId(),
                request.trackSid(),
                request.muted()
        );

        return ResponseEntity.noContent().build();
    }
}
