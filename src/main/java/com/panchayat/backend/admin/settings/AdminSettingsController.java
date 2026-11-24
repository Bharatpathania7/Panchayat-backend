package com.panchayat.backend.admin.settings;

import com.panchayat.backend.auth.service.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * App-wide switches changeable from the admin panel's Settings page, including maintenance mode
 * (enforced for everyone except staff and health checks — see MaintenanceFilter). Turning
 * maintenance on (off -> on) also emails everyone with the app open right now, in the background.
 *
 *   GET /api/v1/admin/settings                           any staff
 *   PUT /api/v1/admin/settings   AppSettingsService.Settings   Super Admin only
 */
@RestController
@RequestMapping("/api/v1/admin/settings")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','MODERATOR')")
public class AdminSettingsController {

    /** The reason a save was refused, readable in the admin panel (the app hides error messages by default). */
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<java.util.Map<String, String>> refused(org.springframework.web.server.ResponseStatusException e) {
        return org.springframework.http.ResponseEntity.status(e.getStatusCode())
                .body(java.util.Map.of("message", e.getReason() == null ? "The settings couldn't be saved." : e.getReason()));
    }

    /** A field with a value the server can't read (text in a number box, an empty number): say which one. */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public org.springframework.http.ResponseEntity<java.util.Map<String, String>> unreadable(org.springframework.http.converter.HttpMessageNotReadableException e) {
        String m = e.getMostSpecificCause() == null ? "" : String.valueOf(e.getMostSpecificCause().getMessage());
        // The JSON path at the end of the message names the field: ...Settings["dailyCheckinCoins"]
        java.util.regex.Matcher f = java.util.regex.Pattern.compile("\\[\"(\\w+)\"\\]").matcher(m);
        String field = null;
        while (f.find()) field = f.group(1);
        return org.springframework.http.ResponseEntity.badRequest()
                .body(java.util.Map.of("message", field == null ? "One of the settings has a value the server can't read. Reload the page and try again." : "The setting \"" + field + "\" has a value the server can't read. Fix it and save again."));
    }

    private static final Logger log = LoggerFactory.getLogger(AdminSettingsController.class);

    private final AppSettingsService settings;
    private final SimpUserRegistry online;
    private final EmailService email;

    public AdminSettingsController(AppSettingsService settings, SimpUserRegistry online, EmailService email) {
        this.settings = settings;
        this.online = online;
        this.email = email;
    }

    @GetMapping
    public AppSettingsService.Settings get() {
        return settings.settings();
    }

    /**
     * What the Settings page saves. updatedAt / updatedBy are NOT read from the page (the server sets them itself), so an
     * old or oddly formatted value sent back by the page can never block a save. Unknown fields are ignored.
     */
    public record SaveBody(boolean maintenance, String maintenanceMessage, Long maintenanceUntil, String announcement,
                           Integer signupBonusCoins, Integer dailyCheckinCoins, Boolean giftingEnabled, Boolean roomCreationEnabled,
                           Integer roomCreateMinLevel, String minAppVersion, Boolean apkBannerEnabled, String apkBannerText, String apkBannerSoon) {
        AppSettingsService.Settings toSettings() {
            return new AppSettingsService.Settings(maintenance, maintenanceMessage, maintenanceUntil, announcement,
                    signupBonusCoins == null ? 0 : signupBonusCoins, dailyCheckinCoins == null ? 0 : dailyCheckinCoins,
                    giftingEnabled == null || giftingEnabled, roomCreationEnabled == null || roomCreationEnabled,
                    roomCreateMinLevel == null ? 0 : roomCreateMinLevel, minAppVersion,
                    apkBannerEnabled == null || apkBannerEnabled, apkBannerText, apkBannerSoon, 0L, null);
        }
    }

    @PutMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public AppSettingsService.Settings save(@RequestBody SaveBody in, Authentication auth) {
        if (in == null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Send the settings.");
        AppSettingsService.Settings body = in.toSettings();
        boolean turningOn = body.maintenance() && !settings.settings().maintenance();
        AppSettingsService.Settings saved = settings.saveSettings(body, auth == null ? null : auth.getName());
        if (turningOn) notifyOnlineUsers(saved.maintenanceMessage());
        return saved;
    }

    /** People with the app open right now: their live connection's name is their email. Runs off-thread so Save doesn't wait on SMTP. */
    private void notifyOnlineUsers(String message) {
        Set<String> emails = new HashSet<>();
        online.getUsers().forEach(u -> {
            String n = u.getName();
            if (n != null && n.contains("@")) emails.add(n.toLowerCase(Locale.ROOT));
        });
        if (emails.isEmpty()) return;
        Thread.ofVirtual().start(() -> {
            for (String e : emails) {
                try {
                    email.sendMaintenanceNotice(e, message);
                    Thread.sleep(200);
                } catch (Exception ex) {
                    log.warn("Couldn't send maintenance notice to {}: {}", e, ex.toString());
                }
            }
            log.info("Maintenance notice sent to {} online users", emails.size());
        });
    }
}
