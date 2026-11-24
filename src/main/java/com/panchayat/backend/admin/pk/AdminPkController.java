package com.panchayat.backend.admin.pk;

import com.panchayat.backend.common.PublicIds;
import com.panchayat.backend.pk.coins.PkCoinService;
import com.panchayat.backend.pk.service.RoomPkService;
import com.panchayat.backend.rooms.service.RoomStateService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * PK system (admin panel): how PK Coins are earned, who holds them, every change, grant / deduct with a reason,
 * and Official PK rooms (a badge and an event title; staff can set up, start and close their PK from here).
 *
 *   GET  /api/v1/admin/pk/settings                      PUT { enabled, percent, minGiftCoins, officialBonus }
 *   GET  /api/v1/admin/pk/summary                       totals
 *   GET  /api/v1/admin/pk/balances?q=                   holders (search by name or ID)
 *   GET  /api/v1/admin/pk/transactions?user=&kind=      the log
 *   POST /api/v1/admin/pk/adjust { user, amount, reason }   grant (amount > 0) or deduct (amount < 0)
 *   GET  /api/v1/admin/pk/rooms                         Official PK rooms
 *   POST /api/v1/admin/pk/rooms { room, title, hostUser? }   make a room an Official PK room (room ID or internal id);
 *                                                        hostUser (optional) also becomes a room admin there, so they
 *                                                        can run its PK (1v1 or multiplayer, any rule/length) from the app
 *   DELETE /api/v1/admin/pk/rooms/{roomId}              no longer official
 *   POST /api/v1/admin/pk/rooms/{roomId}/host { user }  add a host admin to an existing Official PK room
 *   POST /api/v1/admin/pk/rooms/{roomId}/battle { mode, rule, minutes }   set up and start its PK
 *   DELETE /api/v1/admin/pk/rooms/{roomId}/battle       close its PK
 */
@RestController
@RequestMapping("/api/v1/admin/pk")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
public class AdminPkController {

    private final PkCoinService pkCoins;
    private final RoomPkService pk;
    private final RoomStateService roomState;
    private final JdbcTemplate jdbc;

    public AdminPkController(PkCoinService pkCoins, RoomPkService pk, RoomStateService roomState, JdbcTemplate jdbc) {
        this.pkCoins = pkCoins;
        this.pk = pk;
        this.roomState = roomState;
        this.jdbc = jdbc;
    }

    public record AdjustRequest(String user, Long amount, String reason) {}

    public record RoomRequest(String room, String title, String hostUser) {}

    public record HostRequest(String user) {}

    public record BattleRequest(String mode, String rule, Integer minutes) {}

    @GetMapping("/settings")
    public PkCoinService.Settings settings() {
        return pkCoins.settings();
    }

    @PutMapping("/settings")
    public PkCoinService.Settings saveSettings(@RequestBody PkCoinService.Settings body) {
        if (body == null) throw bad("Send the settings.");
        return pkCoins.saveSettings(body);
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        Map<String, Object> m = new LinkedHashMap<>(pkCoins.totals());
        m.put("officialRooms", jdbc.queryForObject("select count(*) from voice_rooms where official_pk", Long.class));
        return m;
    }

    @GetMapping("/balances")
    public List<Map<String, Object>> balances(@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "100") int limit) {
        return pkCoins.balances(q, limit);
    }

    @GetMapping("/transactions")
    public List<Map<String, Object>> transactions(@RequestParam(required = false) String user, @RequestParam(required = false) String kind,
                                                  @RequestParam(defaultValue = "100") int limit) {
        Long id = user == null || user.isBlank() ? null : userId(user);
        if (kind != null && !kind.isBlank() && !Set.of("EARN", "SPEND", "GRANT", "DEDUCT").contains(kind.toUpperCase(Locale.ROOT))) {
            throw bad("kind must be EARN, SPEND, GRANT or DEDUCT");
        }
        return pkCoins.transactions(id, kind, limit);
    }

    @PostMapping("/adjust")
    public Map<String, Object> adjust(@RequestBody AdjustRequest body, Authentication auth) {
        if (body == null || body.amount() == null) throw bad("Enter a number of PK Coins.");
        Long id = userId(body.user());
        long balance = pkCoins.adminChange(id, body.amount(), body.reason(), auth == null ? null : auth.getName());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", String.valueOf(id));
        m.put("balance", balance);
        return m;
    }

    public record ResetRequest(String confirm, String reason) {}

    /** Everyone's PK Coins back to 0 and the PK board starts again. Needs { confirm: "RESET" } so it can't happen by accident. */
    @PostMapping("/reset-all")
    public Map<String, Object> resetAll(@RequestBody ResetRequest body, Authentication auth) {
        if (body == null || !"RESET".equals(body.confirm())) throw bad("Type RESET to confirm.");
        return pkCoins.resetAll(body.reason(), auth == null ? null : auth.getName());
    }

    @GetMapping("/rooms")
    public List<Map<String, Object>> rooms() {
        return jdbc.query("select r.id, r.name, r.status, r.official_pk_title, r.owner_id, u.full_name as owner_name,"
                        + " (select count(*) from voice_room_participants p where p.room_id = r.id and p.status = 'JOINED') as online"
                        + " from voice_rooms r left join users u on u.id = r.owner_id where r.official_pk order by r.id",
                (rs, i) -> {
                    long id = rs.getLong("id");
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("roomId", String.valueOf(id));
                    m.put("roomCode", PublicIds.room(id));
                    m.put("name", rs.getString("name"));
                    m.put("status", rs.getString("status"));
                    m.put("title", rs.getString("official_pk_title"));
                    m.put("ownerName", rs.getString("owner_name"));
                    m.put("online", rs.getLong("online"));
                    m.put("live", pk.isLive(id));
                    // Room admins (added here as host admins, or by the owner): they can run this room's PK from the app
                    m.put("hosts", jdbc.query("select u.id, u.full_name from voice_room_admins a join users u on u.id = a.user_id where a.room_id = ? order by a.id",
                            (hrs, hi) -> Map.of("userId", String.valueOf(hrs.getLong(1)), "name", hrs.getString(2)), id));
                    return m;
                });
    }

    @PostMapping("/rooms")
    @Transactional
    public Map<String, Object> addRoom(@RequestBody RoomRequest body) {
        Long id = roomId(body == null ? null : body.room());
        String title = body == null || body.title() == null || body.title().isBlank() ? null : body.title().strip();
        if (title != null && title.length() > 80) title = title.substring(0, 80);
        jdbc.update("update voice_rooms set official_pk = true, official_pk_title = ? where id = ?", title, id);
        if (body != null && body.hostUser() != null && !body.hostUser().isBlank()) roomState.setAdminByStaff(id, userId(body.hostUser()));
        return Map.of("roomId", String.valueOf(id), "officialPk", true);
    }

    /** Adds another host admin to an Official PK room already made (owner, or run from here again). */
    @PostMapping("/rooms/{roomId:[0-9]+}/host")
    public Map<String, Object> addHost(@PathVariable Long roomId, @RequestBody HostRequest body) {
        requireOfficial(roomId);
        if (body == null || body.user() == null || body.user().isBlank()) throw bad("Type the host's ID.");
        roomState.setAdminByStaff(roomId, userId(body.user()));
        return Map.of("roomId", String.valueOf(roomId), "hostAdded", true);
    }

    @DeleteMapping("/rooms/{roomId:[0-9]+}")
    public Map<String, Object> removeRoom(@PathVariable Long roomId) {
        int n = jdbc.update("update voice_rooms set official_pk = false, official_pk_title = null where id = ? and official_pk", roomId);
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That room is not an Official PK room.");
        return Map.of("roomId", String.valueOf(roomId), "officialPk", false);
    }

    @PostMapping("/rooms/{roomId:[0-9]+}/battle")
    public Map<String, Object> startBattle(@PathVariable Long roomId, @RequestBody BattleRequest body) {
        requireOfficial(roomId);
        if (body == null) throw bad("Pick the mode, rule and minutes.");
        pk.setup(roomId, null, body.mode(), body.rule(), body.minutes(), true);
        return pk.start(roomId, null, true);
    }

    @DeleteMapping("/rooms/{roomId:[0-9]+}/battle")
    public Map<String, Object> closeBattle(@PathVariable Long roomId) {
        requireOfficial(roomId);
        return pk.close(roomId, null, true);
    }

    private void requireOfficial(Long roomId) {
        Boolean ok = jdbc.query("select official_pk from voice_rooms where id = ?", rs -> rs.next() && rs.getBoolean(1), roomId);
        if (!Boolean.TRUE.equals(ok)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That room is not an Official PK room.");
    }

    /** A user's public ID (7 digits), shown ID, or internal id. */
    private Long userId(String q) {
        String t = q == null ? "" : q.strip();
        if (!t.matches("\\d{1,12}")) throw bad("Type the user's ID.");
        long n = Long.parseLong(t);
        List<Long> ids = jdbc.queryForList("select id from users where public_id = ? or display_public_id = ? or id = ?"
                + " order by (public_id = ? or display_public_id = ?) desc limit 1", Long.class, n, n, n, n, n);
        if (ids.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No account with that ID.");
        return ids.get(0);
    }

    /** A room's 7-digit room ID or its internal id. */
    private Long roomId(String q) {
        String t = q == null ? "" : q.strip().replace("#", "");
        if (!t.matches("\\d{1,12}")) throw bad("Type the room ID.");
        long n = Long.parseLong(t);
        List<Long> ids = jdbc.queryForList("select id from voice_rooms where (room_code = ? or id = ?) and status = 'ACTIVE'"
                + " order by (room_code = ?) desc limit 1", Long.class, n, n, n);
        if (ids.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No live room with that ID.");
        return ids.get(0);
    }

    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }
}
