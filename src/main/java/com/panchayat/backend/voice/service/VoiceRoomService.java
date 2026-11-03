package com.panchayat.backend.voice.service;

import com.panchayat.backend.auth.entity.User;
import com.panchayat.backend.auth.repository.UserRepository;
import com.panchayat.backend.voice.dto.CreateVoiceRoomRequest;
import com.panchayat.backend.voice.dto.JoinVoiceRoomResponse;
import com.panchayat.backend.voice.dto.VoiceRoomResponse;
import com.panchayat.backend.voice.entity.VoiceRoom;
import com.panchayat.backend.voice.entity.VoiceRoomParticipant;
import com.panchayat.backend.voice.repository.VoiceRoomParticipantRepository;
import com.panchayat.backend.voice.repository.VoiceRoomRepository;
import com.panchayat.backend.voice.dto.VoiceRoomParticipantResponse;
import com.panchayat.backend.vip.entity.UserEquippedCosmetic;
import com.panchayat.backend.vip.repository.UserEquippedCosmeticRepository;
import com.panchayat.backend.rooms.service.LiveKitPermissionService;
import com.panchayat.backend.rooms.service.RoomStateService;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import io.livekit.server.RoomServiceClient;
import retrofit2.Response;
import java.io.IOException;
import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.panchayat.backend.vip.entity.VipCosmetic;
import com.panchayat.backend.vip.repository.VipCosmeticRepository;

@Service
public class VoiceRoomService {
    /** Effects each person wears from their Bag (spend rewards); everyone sees them. */
    @org.springframework.beans.factory.annotation.Autowired
    private com.panchayat.backend.rewards.service.SpendRewardService rewardBag;


    private final VoiceRoomRepository voiceRoomRepository;
    private final VipCosmeticRepository vipCosmeticRepository;
    private final VoiceRoomParticipantRepository participantRepository;
    private final UserRepository userRepository;
    private final LiveKitTokenService liveKitTokenService;
    private final String liveKitUrl;
    private final String liveKitApiKey;
    private final String liveKitApiSecret;
    private final UserEquippedCosmeticRepository equippedCosmeticRepository;
    private final RoomStateService roomStateService;
    private final com.panchayat.backend.rooms.service.RoomSettingsService roomSettingsService;
    private final LiveKitPermissionService liveKitPermissionService;

    public VoiceRoomService(
            VoiceRoomRepository voiceRoomRepository,
            VoiceRoomParticipantRepository participantRepository,
            UserRepository userRepository,
            VipCosmeticRepository vipCosmeticRepository,
            LiveKitTokenService liveKitTokenService,
            UserEquippedCosmeticRepository equippedCosmeticRepository,
            RoomStateService roomStateService,
            com.panchayat.backend.rooms.service.RoomSettingsService roomSettingsService,
            LiveKitPermissionService liveKitPermissionService,
            @Value("${livekit.url}") String liveKitUrl,
            @Value("${livekit.api-key}") String liveKitApiKey,
            @Value("${livekit.api-secret}") String liveKitApiSecret) {

        this.voiceRoomRepository = voiceRoomRepository;
        this.participantRepository = participantRepository;
        this.userRepository = userRepository;
        this.liveKitTokenService = liveKitTokenService;
        this.liveKitUrl = liveKitUrl;
        this.liveKitApiKey = liveKitApiKey;
        this.liveKitApiSecret = liveKitApiSecret;
        this.vipCosmeticRepository = vipCosmeticRepository;
        this.equippedCosmeticRepository = equippedCosmeticRepository;
        this.roomStateService = roomStateService;
        this.roomSettingsService = roomSettingsService;
        this.liveKitPermissionService = liveKitPermissionService;
    }

    /** Runs after the current transaction commits (or now, outside one). */
    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    // --------------------------------------------------
    // CREATE ROOM
    // --------------------------------------------------

    @Transactional
    public VoiceRoomResponse createRoom(CreateVoiceRoomRequest request) {

        User user = getAuthenticatedUser();

        if (request.name() == null || request.name().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Room name is required"
            );
        }

        VoiceRoom room = new VoiceRoom();

        room.setOwnerId(user.getId());
        room.setName(request.name().trim());
        room.setDescription(normalizeDescription(request.description()));
        room.setRoomType(request.roomType());
        room.setPrivate(request.isPrivate());

        int capacity = request.maxParticipants() == 0
                ? 8
                : request.maxParticipants();

        if (capacity < 2 || capacity > 50) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Room capacity must be between 2 and 50"
            );
        }

        room.setMaxParticipants(capacity);

        VoiceRoom savedRoom = voiceRoomRepository.save(room);

        // Register the room owner as the first participant.
        VoiceRoomParticipant owner = new VoiceRoomParticipant();

        owner.setRoomId(savedRoom.getId());
        owner.setUserId(user.getId());
        owner.setRole("OWNER");
        owner.setStatus("JOINED");
        owner.setMuted(false);
        owner.setJoinedAt(Instant.now());
        owner.setLeftAt(null);

        participantRepository.save(owner);

        return VoiceRoomResponse.from(savedRoom);
    }

    // --------------------------------------------------
    // LIST PUBLIC ROOMS
    // --------------------------------------------------

    @Transactional(readOnly = true)
    public List<VoiceRoomResponse> getPublicRooms() {

        return voiceRoomRepository
                .findByStatusAndIsPrivateFalseOrderByCreatedAtDesc("ACTIVE")
                .stream()
                .map(VoiceRoomResponse::from)
                .toList();
    }

    // --------------------------------------------------
    // GET ROOM DETAILS
    // --------------------------------------------------

    @Transactional(readOnly = true)
    public VoiceRoomResponse getRoomById(Long roomId) {

        validateRoomId(roomId);

        VoiceRoom room = voiceRoomRepository.findById(roomId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Voice room not found"
                ));

        if (!"ACTIVE".equals(room.getStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Voice room not found"
            );
        }

        if (room.isPrivate()) {
            User user = getAuthenticatedUser();

            if (!room.getOwnerId().equals(user.getId())) {
                throw new AccessDeniedException(
                        "You are not allowed to view this private room"
                );
            }
        }

        return VoiceRoomResponse.from(room);
    }

    @Transactional(readOnly = true)
    public List<VoiceRoomParticipantResponse> getParticipants(Long roomId) {

        validateRoomId(roomId);

        VoiceRoom room = voiceRoomRepository.findById(roomId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Voice room not found"
                ));

        if (!"ACTIVE".equals(room.getStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Voice room not found"
            );
        }

        User currentUser = getAuthenticatedUser();

        if (room.isPrivate()
                && !room.getOwnerId().equals(currentUser.getId())) {
            throw new AccessDeniedException(
                    "You are not allowed to view this private room"
            );
        }

        List<VoiceRoomParticipant> present = participantRepository
                .findByRoomIdAndStatusOrderByJoinedAtAsc(roomId, "JOINED");
        java.util.Map<Long, java.util.Map<String, java.util.Map<String, Object>>> worn =
                rewardBag.effectsFor(present.stream().map(VoiceRoomParticipant::getUserId).toList());

        return present
                .stream()
                .map(participant -> {

                    User participantUser = userRepository
                            .findById(participant.getUserId())
                            .orElse(null);

                    Integer vipLevel = resolveVipLevel(participantUser);

                    String badgeUrl = null;
                    String frameUrl = null;
                    String bubbleUrl = null;
                    String entryAnimationUrl = null;

                    if (participantUser != null) {

                        List<UserEquippedCosmetic> equippedCosmetics =
                                equippedCosmeticRepository.findAllByUser_Id(
                                        participantUser.getId()
                                );

                        for (UserEquippedCosmetic equipped : equippedCosmetics) {

                            VipCosmetic cosmetic = equipped.getCosmetic();

                            if (cosmetic == null
                                    || cosmetic.getSecureUrl() == null
                                    || cosmetic.getSecureUrl().isBlank()) {
                                continue;
                            }

                            switch (cosmetic.getCosmeticType()) {
                                case BADGE ->
                                        badgeUrl = cosmetic.getSecureUrl();

                                case FRAME ->
                                        frameUrl = cosmetic.getSecureUrl();

                                case BUBBLE ->
                                        bubbleUrl = cosmetic.getSecureUrl();

                                case ENTRY ->
                                        entryAnimationUrl = cosmetic.getSecureUrl();
                            }
                        }
                    }

                    return new VoiceRoomParticipantResponse(
                            participant.getUserId(),
                            participantUser != null
                                    ? participantUser.getFullName() : null,
                            participantUser != null
                                    ? participantUser.getAvatarUrl() : null,
                            participant.getRole(),
                            participant.isMuted(),
                            participant.getJoinedAt(),
                            vipLevel,
                            badgeUrl,
                            frameUrl,
                            bubbleUrl,
                            entryAnimationUrl,
                            worn.getOrDefault(participant.getUserId(), java.util.Map.of()),
                            com.panchayat.backend.common.PublicIds.user(participant.getUserId())
                    );
                })
                .toList();
    }

    // --------------------------------------------------
    // JOIN ROOM
    // --------------------------------------------------

    @Transactional
    public JoinVoiceRoomResponse joinRoom(Long roomId) {
        return joinRoom(roomId, null);
    }

    @Transactional
    public JoinVoiceRoomResponse joinRoom(Long roomId, String password) {

        validateRoomId(roomId);

        User user = getAuthenticatedUser();

        // Lock the room row to serialize concurrent capacity checks.
        VoiceRoom room = voiceRoomRepository.findByIdForUpdate(roomId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Voice room not found"
                ));

        if (!"ACTIVE".equals(room.getStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Voice room is not available"
            );
        }

        // Private rooms are currently owner-only.
        if (room.isPrivate()
                && !room.getOwnerId().equals(user.getId())) {

            throw new AccessDeniedException(
                    "You are not allowed to join this private room"
            );
        }

        // Kicked (for a while) or banned from this room.
        if (!room.getOwnerId().equals(user.getId())) {
            var ban = roomStateService.activeBan(roomId, user.getId());
            if (ban.isPresent()) {
                Instant until = ban.get().getExpiresAt();
                String why;
                if (until == null) {
                    why = "You are banned from this room";
                } else {
                    long secs = Math.max(1, java.time.Duration.between(Instant.now(), until).getSeconds());
                    long mins = (secs + 59) / 60;
                    why = "You were kicked out of this room. You can join again in "
                            + (mins <= 1 ? "1 minute" : mins + " minutes") + ".";
                }
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, why);
            }
        }

        boolean roomAdmin = roomStateService.isAdmin(roomId, user.getId());

        // Password rooms (the owner and admins don't need it). 423 = locked, so apps
        // can tell it apart from a kick or ban (403).
        if (!roomSettingsService.passwordAccepted(room, user.getId(), password)) {
            throw new ResponseStatusException(
                    HttpStatus.LOCKED,
                    "This room needs the right password"
            );
        }

        // Lock any existing participant records for this user and room.
        List<VoiceRoomParticipant> existingRecords =
                participantRepository.findForUpdate(roomId, user.getId());

        VoiceRoomParticipant participant = existingRecords.stream()
                .filter(p -> "JOINED".equals(p.getStatus()))
                .findFirst()
                .orElse(null);

        // A new active participant must fit within room capacity.
        if (participant == null) {

            long activeCount =
                    participantRepository.countByRoomIdAndStatus(
                            roomId,
                            "JOINED"
                    );

            if (activeCount >= room.getMaxParticipants()) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Voice room is full"
                );
            }

            // Reuse an earlier LEFT record if one exists.
            if (!existingRecords.isEmpty()) {

                participant = existingRecords.get(0);

                participant.setStatus("JOINED");
                participant.setJoinedAt(Instant.now());
                participant.setLeftAt(null);

                if (room.getOwnerId().equals(user.getId())) {
                    participant.setRole("OWNER");
                } else {
                    participant.setRole(roomAdmin ? "MODERATOR" : "MEMBER");
                }

                participant.setMuted(false);

            } else {

                participant = new VoiceRoomParticipant();

                participant.setRoomId(roomId);
                participant.setUserId(user.getId());
                participant.setRole(
                        room.getOwnerId().equals(user.getId())
                                ? "OWNER"
                                : roomAdmin ? "MODERATOR" : "MEMBER"
                );
                participant.setStatus("JOINED");
                participant.setMuted(false);
                participant.setJoinedAt(Instant.now());
                participant.setLeftAt(null);
            }

            participantRepository.save(participant);
        }

        String liveKitRoomName = "panchayat-room-" + room.getId();
        String participantIdentity = "user-" + user.getId();

        // Listeners join without mic permission; taking a seat grants it.
        boolean canPublish = roomStateService.canPublishOnJoin(room, user.getId());

        String token = liveKitTokenService.generateJoinToken(
                liveKitRoomName,
                participantIdentity,
                canPublish
        );
        liveKitPermissionService.rememberTokenGrant(roomId, user.getId(), canPublish);

        Long joinedUserId = user.getId();
        afterCommit(() -> roomStateService.onJoined(roomId, joinedUserId));

        return new JoinVoiceRoomResponse(
                liveKitUrl,
                token,
                liveKitRoomName,
                600
        );
    }
    private Integer resolveVipLevel(User user) {
        if (user == null || !user.isVipActive()) {
            return null;
        }

        Instant expiresAt = user.getVipExpiresAt();

        if (expiresAt != null && !expiresAt.isAfter(Instant.now())) {
            return null;
        }

        // Temporary: User entity mein abhi vipLevel field nahi hai.
        // Actual VIP-level calculation baad mein integrate hogi.
        return 1;
    }

    private String getCosmeticUrl(
            Integer vipLevel,
            VipCosmetic.CosmeticType cosmeticType
    ) {
        if (vipLevel == null) {
            return null;
        }

        return vipCosmeticRepository
                .findByVipLevelAndCosmeticTypeAndFormat(
                        vipLevel,
                        cosmeticType,
                        VipCosmetic.CosmeticFormat.SVGA
                )
                .or(() -> vipCosmeticRepository
                        .findByVipLevelAndCosmeticTypeAndFormat(
                                vipLevel,
                                cosmeticType,
                                VipCosmetic.CosmeticFormat.PNG
                        ))
                .map(VipCosmetic::getSecureUrl)
                .orElse(null);
    }
    // --------------------------------------------------
    // LEAVE ROOM
    // --------------------------------------------------

    @Transactional
    public void leaveRoom(Long roomId) {

        validateRoomId(roomId);

        User user = getAuthenticatedUser();

        VoiceRoom room = voiceRoomRepository.findByIdForUpdate(roomId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Voice room not found"
                ));

        VoiceRoomParticipant participant =
                participantRepository
                        .findByRoomIdAndUserIdAndStatus(
                                roomId,
                                user.getId(),
                                "JOINED"
                        )
                        .orElseThrow(() -> new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "You are not an active participant in this room"
                        ));

        participant.setStatus("LEFT");
        participant.setLeftAt(Instant.now());
        participant.setMuted(false);

        participantRepository.save(participant);

        Long leftUserId = user.getId();
        afterCommit(() -> roomStateService.onLeft(roomId, leftUserId));
    }

    // --------------------------------------------------
    // CLOSE ROOM
    // --------------------------------------------------

    @Transactional
    public void closeRoom(Long roomId) {

        validateRoomId(roomId);

        User user = getAuthenticatedUser();

        VoiceRoom room = voiceRoomRepository.findByIdForUpdate(roomId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Voice room not found"
                ));

        if (!room.getOwnerId().equals(user.getId())) {
            throw new AccessDeniedException(
                    "Only the room owner can close this room"
            );
        }

        if (!"ACTIVE".equals(room.getStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Voice room is already closed"
            );
        }

        room.setStatus("CLOSED");

        // Mark active participants as LEFT in the database.
        List<VoiceRoomParticipant> activeParticipants =
                participantRepository.findByRoomIdAndStatus(
                        roomId,
                        "JOINED"
                );

        Instant now = Instant.now();

        for (VoiceRoomParticipant participant : activeParticipants) {
            participant.setStatus("LEFT");
            participant.setLeftAt(now);
            participant.setMuted(false);
        }

        participantRepository.saveAll(activeParticipants);
        voiceRoomRepository.save(room);

        afterCommit(() -> roomStateService.onRoomClosed(roomId));
    }

    // --------------------------------------------------
    // VALIDATION AND AUTHENTICATION
    // --------------------------------------------------

    private void validateRoomId(Long roomId) {

        if (roomId == null || roomId <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid room ID"
            );
        }
    }

    private User getAuthenticatedUser() {

        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication.getPrincipal() == null
                || "anonymousUser".equals(authentication.getPrincipal())) {

            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Authentication required"
            );
        }

        // Assumes the JWT filter uses email as the principal name.
        String email = authentication.getName();

        return userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED,
                        "Authenticated user not found"
                ));
    }

    private String normalizeDescription(String description) {

        if (description == null || description.isBlank()) {
            return null;
        }

        return description.trim();
    }
    @Transactional(readOnly = true)
    public long getParticipantCount(Long roomId) {

        validateRoomId(roomId);

        VoiceRoom room = voiceRoomRepository.findById(roomId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Voice room not found"
                ));

        if (!"ACTIVE".equals(room.getStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Voice room not found"
            );
        }

        User user = getAuthenticatedUser();

        if (room.isPrivate()
                && !room.getOwnerId().equals(user.getId())) {
            throw new AccessDeniedException(
                    "You are not allowed to view this private room"
            );
        }

        return participantRepository.countByRoomIdAndStatus(
                roomId,
                "JOINED"
        );
    }

    // --------------------------------------------------
    // MUTE / UNMUTE PARTICIPANT (OWNER / MODERATOR)
    // --------------------------------------------------

    @Transactional
    public void setParticipantMuted(
            Long roomId,
            Long targetUserId,
            String trackSid,
            boolean muted
    ) {
        validateRoomId(roomId);

        if (targetUserId == null || targetUserId <= 0
                || trackSid == null || trackSid.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Valid participant ID and track SID are required"
            );
        }

        User currentUser = getAuthenticatedUser();

        VoiceRoom room = voiceRoomRepository.findByIdForUpdate(roomId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Voice room not found"
                ));

        if (!"ACTIVE".equals(room.getStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Voice room is not active"
            );
        }

        VoiceRoomParticipant moderator =
                participantRepository.findByRoomIdAndUserIdAndStatus(
                        roomId, currentUser.getId(), "JOINED"
                ).orElseThrow(() -> new AccessDeniedException(
                        "You must be an active room participant"
                ));

        if (!"OWNER".equals(moderator.getRole())
                && !"MODERATOR".equals(moderator.getRole())) {
            throw new AccessDeniedException(
                    "Only the room owner or a moderator can mute participants"
            );
        }

        VoiceRoomParticipant target =
                participantRepository.findByRoomIdAndUserIdAndStatus(
                        roomId, targetUserId, "JOINED"
                ).orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Target participant is not active in this room"
                ));

        String liveKitRoomName = "panchayat-room-" + roomId;
        String targetIdentity = "user-" + targetUserId;

        try {
            RoomServiceClient liveKitClient = RoomServiceClient.create(
                    liveKitUrl,
                    liveKitApiKey,
                    liveKitApiSecret
            );

            Response<?> response = liveKitClient
                    .mutePublishedTrack(
                            liveKitRoomName,
                            targetIdentity,
                            trackSid,
                            muted
                    )
                    .execute();

            if (!response.isSuccessful()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "LiveKit could not update the participant audio track"
                );
            }

            target.setMuted(muted);
            participantRepository.save(target);

        } catch (IOException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Could not communicate with LiveKit",
                    e
            );
        }
    }

}
