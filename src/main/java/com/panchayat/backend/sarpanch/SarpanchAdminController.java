package com.panchayat.backend.sarpanch;

import com.panchayat.backend.admin.media.AdminMediaService;
import com.panchayat.backend.admin.media.MediaUrls;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;

/**
 * Sarpanch for the Panchayat team (SUPER_ADMIN and ADMIN; others get 403):
 *   GET  /api/v1/admin/sarpanch/profile                 the account as people see it
 *   PUT  /api/v1/admin/sarpanch/profile                 { name, bio, displayId? }
 *   POST /api/v1/admin/sarpanch/profile/photo           multipart file (PNG/JPG)
 *   POST /api/v1/admin/sarpanch/messages                { userIds[≤1000], title?, body, imageUrl?, actionLabel?, actionUrl?, category } → { sent, skipped }
 *   POST /api/v1/admin/sarpanch/broadcasts              { title?, body, …, category, audience, scheduledAt? } → broadcast
 *   GET  /api/v1/admin/sarpanch/broadcasts?page=&size=  { items[], total }
 *   POST /api/v1/admin/sarpanch/broadcasts/{id}/cancel
 *   POST /api/v1/admin/sarpanch/images                  multipart file (≤ 5 MB) → { imageUrl, previewUrl }
 *   GET  /api/v1/admin/sarpanch/templates
 *   PUT  /api/v1/admin/sarpanch/templates/{key}         { title?, body, actionLabel?, actionUrl?, category }
 */
@RestController
@RequestMapping("/api/v1/admin/sarpanch")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
public class SarpanchAdminController {

    private static final long IMAGE_MAX = 5L * 1024 * 1024;
    private final SarpanchService sarpanch;
    private final AdminMediaService media;
    private final JdbcTemplate jdbc;

    public SarpanchAdminController(SarpanchService sarpanch, AdminMediaService media, JdbcTemplate jdbc) {
        this.sarpanch = sarpanch;
        this.media = media;
        this.jdbc = jdbc;
    }

    public record ProfileRequest(String name, String bio, Long displayId) {
    }

    public record MessageRequest(List<Long> userIds, String title, String body, String imageUrl, String actionLabel, String actionUrl, String category) {
    }

    public record BroadcastRequest(String title, String body, String imageUrl, String actionLabel, String actionUrl, String category,
                                   Map<String, Object> audience, Instant scheduledAt) {
    }

    @GetMapping("/profile")
    public Map<String, Object> profile() {
        Map<String, Object> p = new LinkedHashMap<>(sarpanch.profile());
        p.put("displayId", jdbc.queryForObject("select display_public_id from users where id = ?", Long.class, sarpanch.sarpanchId()));
        return p;
    }

    @PutMapping("/profile")
    @Transactional
    public Map<String, Object> saveProfile(@RequestBody ProfileRequest body) {
        if (body == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nothing to save.");
        if (body.displayId() != null) {
            if (body.displayId() < 1000 || body.displayId() > 999_999_999_999L) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The ID must be 4 to 12 digits.");
            Integer taken = jdbc.queryForObject("select count(*) from users where (public_id = ? or display_public_id = ?) and id <> ?", Integer.class, body.displayId(), body.displayId(), sarpanch.sarpanchId());
            if (taken != null && taken > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Someone already has that ID.");
            jdbc.update("update users set display_public_id = ? where id = ?", body.displayId(), sarpanch.sarpanchId());
        }
        sarpanch.saveProfile(body.name(), body.bio(), null);
        return profile();
    }

    @PostMapping(value = "/profile/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> photo(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a picture.");
        if (file.getSize() > IMAGE_MAX) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pictures can be up to 5 MB.");
        var up = media.upload(file, "sarpanch", Set.of("PNG", "JPG", "JPEG"));
        String name = (String) sarpanch.profile().get("name");
        String bio = (String) sarpanch.profile().get("bio");
        sarpanch.saveProfile(name, bio, up.url());
        return profile();
    }

    @PostMapping("/messages")
    public Map<String, Object> send(@RequestBody MessageRequest body) {
        if (body == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Write a message.");
        return sarpanch.sendToUsers(body.userIds(), new SarpanchService.Message(body.title(), body.body(), body.imageUrl(), body.actionLabel(), body.actionUrl(), body.category()));
    }

    @PostMapping("/broadcasts")
    public Map<String, Object> broadcast(@RequestBody BroadcastRequest body, Authentication auth) {
        if (body == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Write a message.");
        return sarpanch.createBroadcast(new SarpanchService.Message(body.title(), body.body(), body.imageUrl(), body.actionLabel(), body.actionUrl(), body.category()),
                body.audience(), body.scheduledAt(), auth == null ? null : auth.getName());
    }

    @GetMapping("/broadcasts")
    public Map<String, Object> broadcasts(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return sarpanch.listBroadcasts(page, size);
    }

    @PostMapping("/broadcasts/{id:[0-9]+}/cancel")
    public Map<String, Object> cancel(@PathVariable Long id) {
        return sarpanch.cancelBroadcast(id);
    }

    @PostMapping(value = "/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> image(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a picture.");
        if (file.getSize() > IMAGE_MAX) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pictures can be up to 5 MB.");
        var up = media.upload(file, "sarpanch/messages", Set.of("PNG", "JPG", "JPEG"));
        // imageUrl goes back into a message; previewUrl is for showing it now
        return Map.of("imageUrl", up.url(), "previewUrl", MediaUrls.link(up.url()));
    }

    @GetMapping("/templates")
    public List<Map<String, Object>> templates() {
        return sarpanch.templates();
    }

    @PutMapping("/templates/{key}")
    public Map<String, Object> saveTemplate(@PathVariable String key, @RequestBody Map<String, Object> body, Authentication auth) {
        return sarpanch.saveTemplate(key, body, auth == null ? null : auth.getName());
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handle(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("message", e.getReason() == null ? "That didn't work" : e.getReason()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> denied(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Only admins can send Sarpanch messages"));
    }
}
