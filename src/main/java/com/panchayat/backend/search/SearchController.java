package com.panchayat.backend.search;

import com.panchayat.backend.common.PublicIds;
import com.panchayat.backend.voice.dto.VoiceRoomResponse;
import com.panchayat.backend.voice.entity.VoiceRoom;
import com.panchayat.backend.voice.repository.VoiceRoomRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

/**
 * Find people and rooms: GET /api/v1/search?q=
 *
 * A 7-digit number finds the person with that public ID and the room with
 * that room ID (an exact match comes first). Text finds people by name and
 * live public rooms by name. Signed-in users only.
 */
@RestController
public class SearchController {

    private static final int LIMIT = 15;

    private final JdbcTemplate jdbc;
    private final VoiceRoomRepository rooms;

    public SearchController(JdbcTemplate jdbc, VoiceRoomRepository rooms) {
        this.jdbc = jdbc;
        this.rooms = rooms;
    }

    @GetMapping("/api/v1/search")
    public Map<String, Object> search(@RequestParam(defaultValue = "") String q) {
        String term = q.trim();
        Map<String, Object> out = new LinkedHashMap<>();
        if (term.isEmpty() || term.length() > 60) {
            out.put("users", List.of());
            out.put("rooms", List.of());
            return out;
        }
        Long code = PublicIds.parse(term);
        String like = "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";

        // People: exact ID first, then names
        List<Map<String, Object>> users = jdbc.query(
                "select id, public_id, full_name, avatar_url, gender, vip_active, vip_level from users"
                        + " where (public_id = ? or (? and full_name ilike ?)) and full_name is not null"
                        + " order by (public_id = ?) desc, full_name limit " + LIMIT,
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("publicId", rs.getLong("public_id"));
                    m.put("fullName", rs.getString("full_name"));
                    m.put("avatarUrl", rs.getString("avatar_url"));
                    m.put("gender", rs.getString("gender"));
                    m.put("vipLevel", rs.getBoolean("vip_active") ? rs.getObject("vip_level") : null);
                    return m;
                },
                code == null ? -1L : code, code == null, like, code == null ? -1L : code);

        // Rooms: an exact room ID (private ones too, so a shared ID always resolves), then live public rooms by name
        List<Long> roomIds = jdbc.queryForList(
                "select id from voice_rooms where status = 'ACTIVE'"
                        + " and (room_code = ? or (? and is_private = false and name ilike ?))"
                        + " order by (room_code = ?) desc, created_at desc limit " + LIMIT,
                Long.class,
                code == null ? -1L : code, code == null, like, code == null ? -1L : code);
        Map<Long, VoiceRoom> byId = new HashMap<>();
        rooms.findAllById(roomIds).forEach(r -> byId.put(r.getId(), r));
        List<VoiceRoomResponse> roomList = roomIds.stream().map(byId::get).filter(Objects::nonNull).map(VoiceRoomResponse::from).toList();

        out.put("users", users);
        out.put("rooms", roomList);
        return out;
    }
}
