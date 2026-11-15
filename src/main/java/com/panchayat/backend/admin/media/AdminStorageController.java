package com.panchayat.backend.admin.media;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Moving the pictures that are still on Cloudinary onto this server (Super Admin only).
 *
 *   POST /api/v1/admin/manage/storage/cloudinary/move     start (runs in the background)
 *   GET  /api/v1/admin/manage/storage/cloudinary/status   progress and the first failures
 */
@RestController
@RequestMapping("/api/v1/admin/manage/storage/cloudinary")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminStorageController {

    private final CloudinaryMoveService mover;

    public AdminStorageController(CloudinaryMoveService mover) {
        this.mover = mover;
    }

    @PostMapping("/move")
    public Map<String, Object> move(Authentication auth) {
        boolean started = mover.start(auth == null ? null : auth.getName());
        Map<String, Object> s = new java.util.LinkedHashMap<>(mover.status());
        s.put("started", started);
        return s;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return mover.status();
    }
}
