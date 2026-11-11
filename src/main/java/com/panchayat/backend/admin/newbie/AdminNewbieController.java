package com.panchayat.backend.admin.newbie;

import com.panchayat.backend.newbie.NewbieService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Admin > Newbie rooms: settings, rooms, hosts and numbers. Every change goes through the admin audit log
 * (the same interceptor as all other admin routes records who did it).
 */
@RestController
@RequestMapping("/api/v1/admin/newbie")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
public class AdminNewbieController {

    private final NewbieService newbie;

    public AdminNewbieController(NewbieService newbie) {
        this.newbie = newbie;
    }

    private static String by(Authentication auth) {
        return auth == null ? null : auth.getName();
    }

    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }

    private static Long id(String v, String what) {
        try {
            return Long.parseLong(v.trim());
        } catch (Exception e) {
            throw bad("Pick " + what + ".");
        }
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        return newbie.configMap();
    }

    @PutMapping("/config")
    public Map<String, Object> saveConfig(@RequestBody Map<String, Object> body) {
        return newbie.saveConfig(body);
    }

    @GetMapping("/analytics")
    public Map<String, Object> analytics() {
        return newbie.analytics();
    }

    @GetMapping("/rooms")
    public Map<String, Object> rooms(@RequestParam(required = false) String status, @RequestParam(required = false) String q,
                                     @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return newbie.adminRooms(status, q, page, size);
    }

    public record CreateBody(String hostId) {
    }

    @PostMapping("/rooms")
    public Map<String, Object> create(@RequestBody CreateBody b, Authentication auth) {
        return newbie.createRoom(id(b == null || b.hostId() == null ? "" : b.hostId(), "a host"), by(auth));
    }

    public record AttachBody(String room) {
    }

    /** Makes a room that already exists (made in the app) a welcome room. */
    @PostMapping("/rooms/attach")
    public Map<String, Object> attach(@RequestBody AttachBody b, Authentication auth) {
        return newbie.attachRoom(b == null ? null : b.room(), by(auth));
    }

    @PostMapping("/rooms/{roomId:[0-9]+}/activate")
    public Map<String, Object> activate(@PathVariable Long roomId) {
        return newbie.setRoomStatus(roomId, "ACTIVE");
    }

    @PostMapping("/rooms/{roomId:[0-9]+}/disable")
    public Map<String, Object> disable(@PathVariable Long roomId) {
        return newbie.setRoomStatus(roomId, "DISABLED");
    }

    @DeleteMapping("/rooms/{roomId:[0-9]+}")
    public Map<String, Object> delete(@PathVariable Long roomId) {
        newbie.deleteRoom(roomId);
        return Map.of("deleted", roomId);
    }

    public record PriorityBody(Integer priority) {
    }

    @PutMapping("/rooms/{roomId:[0-9]+}/priority")
    public Map<String, Object> priority(@PathVariable Long roomId, @RequestBody PriorityBody b) {
        if (b == null || b.priority() == null) throw bad("Enter a priority.");
        return newbie.setPriority(roomId, b.priority());
    }

    public record HostBody(String hostId) {
    }

    @PutMapping("/rooms/{roomId:[0-9]+}/host")
    public Map<String, Object> host(@PathVariable Long roomId, @RequestBody HostBody b, Authentication auth) {
        return newbie.changeHost(roomId, id(b == null || b.hostId() == null ? "" : b.hostId(), "a host"), by(auth));
    }

    public record SeatsBody(Boolean enabled) {
    }

    @PutMapping("/rooms/{roomId:[0-9]+}/extra-seats")
    public Map<String, Object> extraSeats(@PathVariable Long roomId, @RequestBody SeatsBody b) {
        return newbie.setExtraSeats(roomId, null, b != null && Boolean.TRUE.equals(b.enabled()), true);
    }

    @GetMapping("/hosts")
    public List<Map<String, Object>> hosts() {
        return newbie.hosts();
    }

    @PostMapping("/hosts")
    public List<Map<String, Object>> addHost(@RequestBody HostBody b, Authentication auth) {
        return newbie.addHost(id(b == null || b.hostId() == null ? "" : b.hostId(), "a user"), by(auth));
    }

    @DeleteMapping("/hosts/{userId:[0-9]+}")
    public List<Map<String, Object>> removeHost(@PathVariable Long userId) {
        return newbie.removeHost(userId);
    }
}
