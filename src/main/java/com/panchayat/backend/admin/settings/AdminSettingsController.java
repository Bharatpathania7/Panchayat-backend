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

    @PutMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public AppSettingsService.Settings save(@RequestBody AppSettingsService.Settings body, Authentication auth) {
        boolean turningOn = body != null && body.maintenance() && !settings.settings().maintenance();
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
