package com.panchayat.backend.admin.rooms;

import com.panchayat.backend.voice.entity.VoiceRoom;
import com.panchayat.backend.voice.entity.VoiceRoomType;
import com.panchayat.backend.voice.repository.VoiceRoomRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rooms from the admin panel: create a room for any user (it shows up for everyone in the app like any
 * other room), edit or close any room, and make any user an admin of a room (or take that away).
 */
@RestController
@RequestMapping("/api/v1/admin/rooms")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
public class AdminRoomController {

    private final JdbcTemplate jdbc;
    private final VoiceRoomRepository rooms;

    public AdminRoomController(JdbcTemplate jdbc, VoiceRoomRepository rooms) {
        this.jdbc = jdbc;
        this.rooms = rooms;
    }

    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }

    private Map<String, Object> row(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        long id = rs.getLong("id");
        m.put("id", id);
        m.put("name", rs.getString("name"));
        m.put("description", rs.getString("description"));
        m.put("category", rs.getString("category"));
        m.put("roomType", rs.getString("room_type"));
        m.put("isPrivate", rs.getBoolean("is_private"));
        m.put("maxParticipants", rs.getInt("max_participants"));
        m.put("status", rs.getString("status"));
        m.put("ownerId", rs.getLong("owner_id"));
        m.put("owner", rs.getString("owner_name"));
        m.put("ownerPublicId", rs.getObject("owner_public_id"));
        m.put("official", rs.getBoolean("official"));
        m.put("online", rs.getLong("online"));
        m.put("createdAt", rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toInstant().toString());
        m.put("admins", jdbc.queryForList("select a.user_id as \"userId\", u.full_name as name, u.public_id as \"publicId\" from voice_room_admins a join users u on u.id = a.user_id where a.room_id = ? order by a.id", id));
        return m;
    }

    private static final String SELECT = "select r.id, r.name, r.description, r.category, r.room_type, r.is_private, r.official, r.max_participants, r.status, r.owner_id, r.created_at,"
            + " u.full_name as owner_name, u.public_id as owner_public_id,"
            + " (select count(*) from voice_room_participants p where p.room_id = r.id and p.status = 'JOINED') as online"
            + " from voice_rooms r left join users u on u.id = r.owner_id";

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String q) {
        String where = " where r.parivar_id is null";
        Object[] args = new Object[0];
        if (q != null && !q.isBlank()) {
            String like = "%" + q.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            where += " and (r.name ilike ? or u.full_name ilike ? or cast(r.id as text) = ?)";
            args = new Object[]{like, like, q.trim()};
        }
        return jdbc.query(SELECT + where + " order by (r.status = 'ACTIVE') desc, r.created_at desc limit 200", (rs, i) -> row(rs), args);
    }

    @GetMapping("/{id:[0-9]+}")
    public Map<String, Object> one(@PathVariable Long id) {
        List<Map<String, Object>> l = jdbc.query(SELECT + " where r.id = ?", (rs, i) -> row(rs), id);
        if (l.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found");
        return l.get(0);
    }

    public record RoomBody(String name, String description, String category, String roomType, Boolean isPrivate, Integer maxParticipants, Long ownerId, String password, Boolean official) {
    }

    /** The Sarpanch account (the official user). */
    private Long sarpanchId() {
        List<Long> l = jdbc.queryForList("select id from users where official order by id limit 1", Long.class);
        if (l.isEmpty()) throw bad("The Sarpanch account is missing.");
        return l.get(0);
    }

    private void userMustExist(Long id) {
        Long n = jdbc.queryForObject("select count(*) from users where id = ?", Long.class, id);
        if (n == null || n == 0) throw bad("That user doesn't exist.");
    }

    @PostMapping
    @Transactional
    public Map<String, Object> create(@RequestBody RoomBody b) {
        if (b == null || b.name() == null || b.name().strip().length() < 3) throw bad("The room name must be at least 3 letters.");
        boolean official = Boolean.TRUE.equals(b.official());
        Long owner = official ? sarpanchId() : b.ownerId();
        if (owner == null) throw bad("Pick the user who will own this room.");
        userMustExist(owner);
        VoiceRoom room = new VoiceRoom();
        room.setOwnerId(owner);
        room.setOfficial(official);
        room.setName(b.name().strip().substring(0, Math.min(100, b.name().strip().length())));
        room.setDescription(b.description() == null || b.description().isBlank() ? null : b.description().strip().substring(0, Math.min(500, b.description().strip().length())));
        VoiceRoomType type = VoiceRoomType.VOICE;
        if (b.roomType() != null && !b.roomType().isBlank()) {
            try {
                type = VoiceRoomType.valueOf(b.roomType().strip().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw bad("Unknown room type.");
            }
        }
        room.setRoomType(type);
        room.setPrivate(Boolean.TRUE.equals(b.isPrivate()));
        int cap = b.maxParticipants() == null || b.maxParticipants() == 0 ? 8 : b.maxParticipants();
        if (cap < 2 || cap > 50) throw bad("Room capacity must be between 2 and 50.");
        room.setMaxParticipants(cap);
        if (b.category() != null && !b.category().isBlank()) room.setCategory(b.category().strip().substring(0, Math.min(30, b.category().strip().length())));
        if (room.isPrivate() || (b.password() != null && !b.password().isBlank())) {
            String pw = b.password() == null ? "" : b.password().strip();
            if (!pw.matches("\\d{4}")) throw bad("A private room needs a 4 digit password.");
            room.setPassword(pw);
        }
        VoiceRoom saved = rooms.saveAndFlush(room);
        return one(saved.getId());
    }

    @PatchMapping("/{id:[0-9]+}")
    @Transactional
    public Map<String, Object> update(@PathVariable Long id, @RequestBody RoomBody b) {
        VoiceRoom r = rooms.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found"));
        if (b.name() != null) {
            if (b.name().strip().length() < 3) throw bad("The room name must be at least 3 letters.");
            r.setName(b.name().strip().substring(0, Math.min(100, b.name().strip().length())));
        }
        if (b.description() != null) r.setDescription(b.description().isBlank() ? null : b.description().strip().substring(0, Math.min(500, b.description().strip().length())));
        if (b.category() != null) r.setCategory(b.category().isBlank() ? null : b.category().strip().substring(0, Math.min(30, b.category().strip().length())));
        if (b.maxParticipants() != null) {
            if (b.maxParticipants() < 2 || b.maxParticipants() > 50) throw bad("Room capacity must be between 2 and 50.");
            r.setMaxParticipants(b.maxParticipants());
        }
        if (b.isPrivate() != null) r.setPrivate(b.isPrivate());
        if (b.password() != null) {
            String pw = b.password().strip();
            if (!pw.isEmpty() && !pw.matches("\\d{4}")) throw bad("The password must be 4 digits.");
            r.setPassword(pw.isEmpty() ? null : pw);
        }
        if (b.official() != null && b.official() != r.isOfficial()) {
            r.setOfficial(b.official());
            if (b.official()) r.setOwnerId(sarpanchId());
        }
        if (!r.isOfficial() && b.ownerId() != null && !b.ownerId().equals(r.getOwnerId())) {
            userMustExist(b.ownerId());
            r.setOwnerId(b.ownerId());
        }
        rooms.saveAndFlush(r);
        return one(id);
    }

    /** Closes a room: it leaves the app's list and everyone inside is marked as left. */
    @DeleteMapping("/{id:[0-9]+}")
    @Transactional
    public Map<String, Object> close(@PathVariable Long id) {
        if (jdbc.update("update voice_rooms set status = 'CLOSED' where id = ?", id) == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found");
        jdbc.update("update voice_room_participants set status = 'LEFT', left_at = now() where room_id = ? and status = 'JOINED'", id);
        return Map.of("id", id, "status", "CLOSED");
    }

    /** Opens a closed room again. */
    @PostMapping("/{id:[0-9]+}/reopen")
    public Map<String, Object> reopen(@PathVariable Long id) {
        if (jdbc.update("update voice_rooms set status = 'ACTIVE' where id = ?", id) == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found");
        return one(id);
    }

    public record AdminBody(Long userId) {
    }

    /** Makes a user an admin of the room: they can moderate seats, the mic queue, kick and ban there. */
    @PostMapping("/{id:[0-9]+}/admins")
    @Transactional
    public Map<String, Object> addAdmin(@PathVariable Long id, @RequestBody AdminBody b) {
        if (b == null || b.userId() == null) throw bad("Pick a user.");
        List<Long> owner = jdbc.queryForList("select owner_id from voice_rooms where id = ?", Long.class, id);
        if (owner.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found");
        userMustExist(b.userId());
        if (b.userId().equals(owner.get(0))) throw bad("They own this room already.");
        if (jdbc.queryForObject("select count(*) from voice_room_admins where room_id = ?", Long.class, id) >= 20) throw bad("A room can have up to 20 admins.");
        // An admin can't be banned from the room they help run
        jdbc.update("delete from voice_room_bans where room_id = ? and user_id = ?", id, b.userId());
        jdbc.update("insert into voice_room_admins (room_id, user_id, created_by) values (?, ?, ?) on conflict (room_id, user_id) do nothing", id, b.userId(), owner.get(0));
        return one(id);
    }

    @DeleteMapping("/{id:[0-9]+}/admins/{userId:[0-9]+}")
    public Map<String, Object> removeAdmin(@PathVariable Long id, @PathVariable Long userId) {
        jdbc.update("delete from voice_room_admins where room_id = ? and user_id = ?", id, userId);
        return one(id);
    }
}
