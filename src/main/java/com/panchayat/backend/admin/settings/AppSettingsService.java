package com.panchayat.backend.admin.settings;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.util.concurrent.atomic.AtomicReference;

/**
 * App-wide switches changed from the admin panel's Settings page. Maintenance mode is checked by
 * MaintenanceFilter on every request, so it is cached and refreshed only when settings are saved
 * (not read from the database on every request).
 */
@Service
public class AppSettingsService {

    private final JdbcTemplate jdbc;
    private final AtomicReference<Settings> cache = new AtomicReference<>();

    public AppSettingsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** maintenanceUntil: expected end time (epoch ms), shown to users as a countdown. Null = no estimate given.
     *  It does not turn maintenance off by itself — staff still do that by hand once the fix is confirmed done. */
    public record Settings(boolean maintenance, String maintenanceMessage, Long maintenanceUntil, String announcement,
                            int signupBonusCoins, int dailyCheckinCoins, boolean giftingEnabled, boolean roomCreationEnabled,
                            int roomCreateMinLevel, String minAppVersion, long updatedAt, String updatedBy) {}

    public Settings settings() {
        Settings s = cache.get();
        return s != null ? s : reload();
    }

    private Settings reload() {
        Settings s = jdbc.query("select maintenance, maintenance_message, maintenance_until, announcement, signup_bonus_coins,"
                + " daily_checkin_coins, gifting_enabled, room_creation_enabled, room_create_min_level, min_app_version, updated_at, updated_by"
                + " from app_settings where id = 1", rs -> rs.next() ? new Settings(
                        rs.getBoolean(1), rs.getString(2),
                        rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant().toEpochMilli(),
                        rs.getString(4), rs.getInt(5), rs.getInt(6), rs.getBoolean(7), rs.getBoolean(8), rs.getInt(9), rs.getString(10),
                        rs.getTimestamp(11).toInstant().toEpochMilli(), rs.getString(12))
                : new Settings(false, "Panchayat is getting an upgrade. We'll be back in a few minutes.", null, "", 500, 50, true, true, 0, "1.0.0", 0L, null));
        cache.set(s);
        return s;
    }

    /** Hot path for MaintenanceFilter and StatusController: no database hit per request. */
    public boolean isMaintenance() {
        return settings().maintenance();
    }

    public String maintenanceMessage() {
        String m = settings().maintenanceMessage();
        return m == null || m.isBlank() ? "Panchayat is getting an upgrade. We'll be back in a few minutes." : m;
    }

    public Long maintenanceUntil() {
        return settings().maintenanceUntil();
    }

    @Transactional
    public Settings saveSettings(Settings s, String adminName) {
        if (s == null) throw bad("Send the settings.");
        if (s.signupBonusCoins() < 0) throw bad("Sign-up bonus must be 0 or more.");
        if (s.dailyCheckinCoins() < 0) throw bad("Daily check-in reward must be 0 or more.");
        if (s.roomCreateMinLevel() < 0) throw bad("Room creation level must be 0 or more.");
        if (s.minAppVersion() == null || s.minAppVersion().isBlank()) throw bad("Enter the oldest app version allowed.");
        jdbc.update("update app_settings set maintenance = ?, maintenance_message = ?, maintenance_until = ?, announcement = ?,"
                        + " signup_bonus_coins = ?, daily_checkin_coins = ?, gifting_enabled = ?, room_creation_enabled = ?,"
                        + " room_create_min_level = ?, min_app_version = ?, updated_at = now(), updated_by = ? where id = 1",
                s.maintenance(), s.maintenanceMessage() == null ? "" : s.maintenanceMessage(),
                s.maintenanceUntil() == null ? null : new Timestamp(s.maintenanceUntil()),
                s.announcement() == null ? "" : s.announcement(),
                s.signupBonusCoins(), s.dailyCheckinCoins(), s.giftingEnabled(), s.roomCreationEnabled(), s.roomCreateMinLevel(),
                s.minAppVersion(), adminName);
        return reload();
    }

    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }
}
