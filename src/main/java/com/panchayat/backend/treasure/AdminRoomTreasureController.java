package com.panchayat.backend.treasure;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Room treasure in the admin panel.
 *
 *   GET    /api/v1/admin/manage/room-treasure            settings and all boxes
 *   PUT    /api/v1/admin/manage/room-treasure/settings   { enabled, bonusFollow, bonusGift, bonusMessage }
 *   POST   /api/v1/admin/manage/room-treasure/boxes      { minutes, coins, vipOnly, active }
 *   PUT    /api/v1/admin/manage/room-treasure/boxes/{id}
 *   DELETE /api/v1/admin/manage/room-treasure/boxes/{id}
 */
@RestController
@RequestMapping("/api/v1/admin/manage/room-treasure")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
public class AdminRoomTreasureController {

    private final JdbcTemplate jdbc;
    private final RoomTreasureService treasure;

    public AdminRoomTreasureController(JdbcTemplate jdbc, RoomTreasureService treasure) {
        this.jdbc = jdbc;
        this.treasure = treasure;
    }

    public record SettingsBody(Boolean enabled, Integer bonusFollow, Integer bonusGift, Integer bonusMessage) {
    }

    public record BoxBody(Integer minutes, Long coins, Boolean vipOnly, Boolean active) {
    }

    private Map<String, Object> all() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("settings", treasure.settings());
        out.put("boxes", jdbc.query("select id, minutes, reward_coins, vip_only, active from room_treasure_boxes order by vip_only, minutes, id", (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", String.valueOf(rs.getLong("id")));
            m.put("minutes", rs.getInt("minutes"));
            m.put("coins", rs.getLong("reward_coins"));
            m.put("vipOnly", rs.getBoolean("vip_only"));
            m.put("active", rs.getBoolean("active"));
            return m;
        }));
        return out;
    }

    @GetMapping
    public Map<String, Object> get() {
        return all();
    }

    @PutMapping("/settings")
    public Map<String, Object> putSettings(@RequestBody SettingsBody b) {
        if (b == null || b.enabled() == null || b.bonusFollow() == null || b.bonusGift() == null || b.bonusMessage() == null) throw bad("Send enabled and the three task minutes.");
        for (int v : new int[]{b.bonusFollow(), b.bonusGift(), b.bonusMessage()}) if (v < 0 || v > 120) throw bad("Task minutes must be 0 to 120.");
        Long active = jdbc.queryForObject("select count(*) from room_treasure_boxes where active", Long.class);
        if (b.enabled() && (active == null || active == 0)) throw bad("Add at least one box before switching this on.");
        jdbc.update("update room_treasure_settings set enabled = ?, bonus_follow = ?, bonus_gift = ?, bonus_message = ? where id = 1", b.enabled(), b.bonusFollow(), b.bonusGift(), b.bonusMessage());
        return all();
    }

    private static void check(BoxBody b) {
        if (b == null || b.minutes() == null || b.coins() == null) throw bad("Send minutes and coins.");
        if (b.minutes() < 1 || b.minutes() > 1440) throw bad("Minutes must be 1 to 1440.");
        if (b.coins() < 0 || b.coins() > 100_000_000L) throw bad("Coins must be 0 to 100,000,000.");
    }

    @PostMapping("/boxes")
    public Map<String, Object> add(@RequestBody BoxBody b) {
        check(b);
        jdbc.update("insert into room_treasure_boxes (minutes, reward_coins, vip_only, active) values (?, ?, ?, ?)", b.minutes(), b.coins(), Boolean.TRUE.equals(b.vipOnly()), b.active() == null || b.active());
        return all();
    }

    @PutMapping("/boxes/{id:[0-9]+}")
    public Map<String, Object> save(@PathVariable long id, @RequestBody BoxBody b) {
        check(b);
        int n = jdbc.update("update room_treasure_boxes set minutes = ?, reward_coins = ?, vip_only = ?, active = ? where id = ?", b.minutes(), b.coins(), Boolean.TRUE.equals(b.vipOnly()), b.active() == null || b.active(), id);
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That box doesn't exist.");
        return all();
    }

    @DeleteMapping("/boxes/{id:[0-9]+}")
    public Map<String, Object> delete(@PathVariable long id) {
        jdbc.update("delete from room_treasure_boxes where id = ?", id);
        return all();
    }

    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }
}
