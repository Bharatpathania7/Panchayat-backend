package com.panchayat.backend.rooms.service;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import com.panchayat.backend.rooms.entity.RoomFollower;
import com.panchayat.backend.rooms.entity.RoomMember;
import com.panchayat.backend.rooms.repository.RoomFollowerRepository;
import com.panchayat.backend.rooms.repository.RoomMemberRepository;
import com.panchayat.backend.voice.dto.VoiceRoomResponse;
import com.panchayat.backend.voice.entity.VoiceRoom;
import com.panchayat.backend.voice.repository.VoiceRoomRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Room settings (name, topic, tag, cover, announcement, password, display and
 * chat settings) and room membership / following.
 *
 * Settings can be changed by the owner and admins. After every change the
 * room is broadcast to /topic/voice-rooms/{roomId}/info so everyone in it
 * (and anyone looking at it) sees the new version at once.
 */
@Service
public class RoomSettingsService {

    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private com.panchayat.backend.social.invites.InviteService invites;


    private static final Logger log = LoggerFactory.getLogger(RoomSettingsService.class);

    private static final Pattern PASSWORD = Pattern.compile("^[0-9]{4}$");
    private static final Pattern COVER_WORD = Pattern.compile("^[a-z-]{1,20}$");
    private static final long MAX_COVER_SIZE = 5L * 1024 * 1024;
    private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    /** Wrong password guesses allowed per person and room before a pause. */
    private static final int MAX_WRONG = 8;
    private static final Duration WRONG_WINDOW = Duration.ofMinutes(10);

    private final VoiceRoomRepository roomRepository;
    private final RoomMemberRepository memberRepository;
    private final RoomFollowerRepository followerRepository;
    private final RoomStateService roomState;
    private final SimpMessagingTemplate messaging;
    private final Cloudinary cloudinary;

    /** "roomId:userId" -> recent wrong password attempts. */
    private final Map<String, Deque<Instant>> wrongGuesses = new ConcurrentHashMap<>();

    public RoomSettingsService(
            VoiceRoomRepository roomRepository,
            RoomMemberRepository memberRepository,
            RoomFollowerRepository followerRepository,
            RoomStateService roomState,
            SimpMessagingTemplate messaging,
            Cloudinary cloudinary
    ) {
        this.roomRepository = roomRepository;
        this.memberRepository = memberRepository;
        this.followerRepository = followerRepository;
        this.roomState = roomState;
        this.messaging = messaging;
        this.cloudinary = cloudinary;
    }

    /** Fields left null are not changed. password "" removes it; announcement "" clears it. */
    public record SettingsUpdate(
            String name,
            String description,
            String category,
            String coverTheme,
            String coverMotif,
            Boolean removeCoverImage,
            String announcement,
            String password,
            Map<String, Object> settings
    ) {
    }

    // ------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------

    public VoiceRoomResponse update(Long roomId, Long actor, SettingsUpdate u) {
        VoiceRoom room = activeRoom(roomId);
        requireMod(room, actor);
        if (u == null) throw bad("Nothing to change");

        List<String> changed = new ArrayList<>();

        if (u.name() != null) {
            String name = u.name().trim();
            if (name.length() < 2 || name.length() > 100) throw bad("The title must be 2 to 100 characters");
            room.setName(name);
            changed.add("name");
        }
        if (u.description() != null) {
            String d = u.description().trim();
            if (d.length() > 500) throw bad("The topic can be at most 500 characters");
            room.setDescription(d.isEmpty() ? null : d);
            changed.add("description");
        }
        if (u.category() != null) {
            String c = u.category().trim();
            if (c.length() > 30) throw bad("The tag can be at most 30 characters");
            room.setCategory(c.isEmpty() ? null : c);
            changed.add("category");
        }
        if (u.coverTheme() != null || u.coverMotif() != null) {
            if (u.coverTheme() == null || u.coverMotif() == null
                    || !COVER_WORD.matcher(u.coverTheme()).matches()
                    || !COVER_WORD.matcher(u.coverMotif()).matches()) {
                throw bad("Pick one of the covers");
            }
            room.setCoverTheme(u.coverTheme());
            room.setCoverMotif(u.coverMotif());
            changed.add("cover");
        }
        if (Boolean.TRUE.equals(u.removeCoverImage())) {
            deleteCoverImage(room);
            changed.add("coverImage");
        }
        if (u.announcement() != null) {
            String a = u.announcement().trim();
            if (a.length() > 500) throw bad("The announcement can be at most 500 characters");
            room.setAnnouncement(a.isEmpty() ? null : a);
            changed.add("announcement");
        }
        if (u.password() != null) {
            String p = u.password().trim();
            if (!p.isEmpty() && !PASSWORD.matcher(p).matches()) throw bad("The password must be 4 digits");
            room.setPassword(p.isEmpty() ? null : p);
            changed.add(p.isEmpty() ? "passwordRemoved" : "password");
        }
        if (u.settings() != null && !u.settings().isEmpty()) {
            Map<String, Object> clean = RoomSettingsJson.clean(u.settings());
            if (clean.isEmpty()) throw bad("Unknown setting");
            Map<String, Object> merged = RoomSettingsJson.read(room.getSettings());
            merged.putAll(clean);
            room.setSettings(RoomSettingsJson.write(merged));
            changed.addAll(clean.keySet());
        }

        if (changed.isEmpty()) throw bad("Nothing to change");
        VoiceRoom saved = roomRepository.save(room);
        return broadcast(saved, "SETTINGS_CHANGED", actor, changed);
    }

    /** For the owner and admins: the current password (null if none). */
    public Map<String, Object> privateSettings(Long roomId, Long actor) {
        VoiceRoom room = activeRoom(roomId);
        requireMod(room, actor);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("password", room.getPassword());
        return out;
    }

    /**
     * Is this the room's password? The owner and admins never need it.
     * Too many wrong guesses in a row pause further tries for a while.
     */
    public boolean checkPassword(Long roomId, Long userId, String password) {
        VoiceRoom room = activeRoom(roomId);
        return passwordAccepted(room, userId, password);
    }

    /** Used by join as well as the check endpoint. */
    public boolean passwordAccepted(VoiceRoom room, Long userId, String password) {
        String required = room.getPassword();
        if (required == null || required.isBlank()) return true;
        if (Objects.equals(room.getOwnerId(), userId) || roomState.isAdmin(room.getId(), userId)) return true;
        // An invite from someone in the room (sent in a chat) lets you in without the password
        if (invites.inviteAccepted(room.getId(), password)) return true;

        String key = room.getId() + ":" + userId;
        Deque<Instant> recent = wrongGuesses.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (recent) {
            Instant cutoff = Instant.now().minus(WRONG_WINDOW);
            while (!recent.isEmpty() && recent.peekFirst().isBefore(cutoff)) recent.pollFirst();
            if (recent.size() >= MAX_WRONG) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "Too many wrong passwords. Try again in a few minutes.");
            }
            boolean ok = password != null && required.equals(password.trim());
            if (ok) recent.clear(); else recent.addLast(Instant.now());
            return ok;
        }
    }

    public VoiceRoomResponse uploadCover(Long roomId, Long actor, MultipartFile file) {
        VoiceRoom room = activeRoom(roomId);
        requireMod(room, actor);
        if (file == null || file.isEmpty()) throw bad("Pick a picture");
        if (file.getSize() > MAX_COVER_SIZE) throw bad("The picture can be at most 5 MB");
        String type = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (!IMAGE_TYPES.contains(type)) throw bad("Use a JPG, PNG or WebP picture");

        Map<?, ?> uploaded;
        try {
            uploaded = cloudinary.uploader().upload(file.getBytes(), ObjectUtils.asMap(
                    "folder", "panchayat/rooms/" + roomId + "/cover",
                    "resource_type", "image",
                    "overwrite", true
            ));
        } catch (Exception e) {
            log.warn("Room cover upload failed. room={}: {}", roomId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Couldn't upload the picture. Try again.");
        }
        Object url = uploaded.get("secure_url");
        Object publicId = uploaded.get("public_id");
        if (!(url instanceof String u) || !(publicId instanceof String id)) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Couldn't upload the picture. Try again.");
        }

        String oldId = room.getCoverImagePublicId();
        room.setCoverImageUrl(u);
        room.setCoverImagePublicId(id);
        VoiceRoom saved = roomRepository.save(room);
        if (oldId != null && !oldId.equals(id)) destroyQuietly(oldId);
        return broadcast(saved, "SETTINGS_CHANGED", actor, List.of("coverImage"));
    }

    // ------------------------------------------------------------------
    // Membership and following
    // ------------------------------------------------------------------

    public VoiceRoomResponse setMember(Long roomId, Long userId, boolean on) {
        VoiceRoom room = activeRoom(roomId);
        if (on) {
            if (!memberRepository.existsByRoomIdAndUserId(roomId, userId)) {
                try {
                    memberRepository.save(new RoomMember(roomId, userId));
                } catch (DataIntegrityViolationException ignored) {
                    // joined at the same moment from another device
                }
            }
        } else {
            memberRepository.deleteByRoomIdAndUserId(roomId, userId);
            roomState.dropAdminRole(roomId, userId);
        }
        room.setMemberCount((int) memberRepository.countByRoomId(roomId));
        return broadcast(roomRepository.save(room), on ? "MEMBER_JOINED" : "MEMBER_LEFT", userId, List.of("memberCount"));
    }

    public VoiceRoomResponse setFollowing(Long roomId, Long userId, boolean on) {
        VoiceRoom room = activeRoom(roomId);
        if (on) {
            if (!followerRepository.existsByRoomIdAndUserId(roomId, userId)) {
                try {
                    followerRepository.save(new RoomFollower(roomId, userId));
                } catch (DataIntegrityViolationException ignored) {
                    // followed at the same moment from another device
                }
            }
        } else {
            followerRepository.deleteByRoomIdAndUserId(roomId, userId);
            roomState.dropAdminRole(roomId, userId);
        }
        room.setFanCount((int) followerRepository.countByRoomId(roomId));
        return broadcast(roomRepository.save(room), on ? "FOLLOWED" : "UNFOLLOWED", userId, List.of("fanCount"));
    }

    /** Room ids you joined and follow. */
    public Map<String, List<String>> relations(Long userId) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        out.put("joined", memberRepository.findByUserId(userId).stream().map(m -> String.valueOf(m.getRoomId())).toList());
        out.put("followed", followerRepository.findByUserId(userId).stream().map(f -> String.valueOf(f.getRoomId())).toList());
        return out;
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private VoiceRoomResponse broadcast(VoiceRoom room, String type, Long actor, List<String> changed) {
        VoiceRoomResponse response = VoiceRoomResponse.from(room);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", type);
        event.put("actorId", actor == null ? null : String.valueOf(actor));
        event.put("changed", changed);
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("roomId", String.valueOf(room.getId()));
        message.put("room", response);
        message.put("event", event);
        Object payload = message;
        messaging.convertAndSend("/topic/voice-rooms/" + room.getId() + "/info", payload);
        return response;
    }

    private void deleteCoverImage(VoiceRoom room) {
        String oldId = room.getCoverImagePublicId();
        room.setCoverImageUrl(null);
        room.setCoverImagePublicId(null);
        if (oldId != null) destroyQuietly(oldId);
    }

    private void destroyQuietly(String publicId) {
        try {
            cloudinary.uploader().destroy(publicId, ObjectUtils.asMap("resource_type", "image"));
        } catch (Exception e) {
            log.debug("Old room cover not deleted: {}", e.getMessage());
        }
    }

    private VoiceRoom activeRoom(Long roomId) {
        if (roomId == null || roomId <= 0) throw bad("Invalid room ID");
        VoiceRoom room = roomRepository.findById(roomId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Voice room not found"));
        if (!"ACTIVE".equals(room.getStatus())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Voice room is not available");
        }
        return room;
    }

    private void requireMod(VoiceRoom room, Long actor) {
        if (!Objects.equals(room.getOwnerId(), actor) && !roomState.isAdmin(room.getId(), actor)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the owner or an admin can change room settings");
        }
    }

    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }
}
