package com.panchayat.backend.common.controller;

import com.panchayat.backend.admin.settings.AppSettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public, unauthenticated: lets the website poll whether maintenance mode is on without needing a
 * request that MaintenanceFilter would otherwise block, and the home-screen announcement banner
 * text (Settings page). Always answers, maintenance or not.
 *
 *   GET /api/v1/status   { maintenance, message, until, announcement }
 */
@RestController
@RequestMapping("/api/v1/status")
public class StatusController {

    public record Status(boolean maintenance, String message, Long until, String announcement) {}

    private final AppSettingsService settings;

    public StatusController(AppSettingsService settings) {
        this.settings = settings;
    }

    @GetMapping
    public Status status() {
        AppSettingsService.Settings s = settings.settings();
        return new Status(s.maintenance(), s.maintenanceMessage(), s.maintenanceUntil(), s.announcement());
    }
}
