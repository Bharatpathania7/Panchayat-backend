package com.panchayat.backend.festival;

import com.panchayat.backend.admin.media.AdminMediaService;
import com.panchayat.backend.admin.media.MediaUrls;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/** The Festival Manager in the admin panel: festivals, the draft, publishing, scheduling, rollback and the pictures. */
@RestController
@RequestMapping("/api/v1/admin/festivals")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
public class FestivalAdminController {

    private final FestivalService festivals;
    private final AdminMediaService media;

    public FestivalAdminController(FestivalService festivals, AdminMediaService media) {
        this.festivals = festivals;
        this.media = media;
    }

    private static String by(Authentication auth) {
        return auth == null ? null : auth.getName();
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return festivals.list();
    }

    public record NameBody(String name) {
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody NameBody b, Authentication auth) {
        return festivals.create(b == null ? null : b.name(), by(auth));
    }

    @GetMapping("/{id:[0-9]+}")
    public Map<String, Object> get(@PathVariable Long id) {
        return festivals.get(id);
    }

    public record DraftBody(String name, Object config) {
    }

    /** Saves the draft only. Nothing here reaches users. */
    @PutMapping("/{id:[0-9]+}/draft")
    public Map<String, Object> saveDraft(@PathVariable Long id, @RequestBody DraftBody b, Authentication auth) {
        return festivals.saveDraft(id, b.name(), b.config(), by(auth));
    }

    @PostMapping("/{id:[0-9]+}/publish")
    public Map<String, Object> publish(@PathVariable Long id, @RequestBody FestivalService.PublishRequest b, Authentication auth) {
        return festivals.publish(id, b, by(auth));
    }

    @PostMapping("/{id:[0-9]+}/discard")
    public Map<String, Object> discard(@PathVariable Long id, Authentication auth) {
        return festivals.discard(id, by(auth));
    }

    @PostMapping("/{id:[0-9]+}/rollback/{versionId:[0-9]+}")
    public Map<String, Object> rollback(@PathVariable Long id, @PathVariable Long versionId, Authentication auth) {
        return festivals.rollback(id, versionId, by(auth));
    }

    public record OnBody(Boolean on) {
    }

    @PostMapping("/{id:[0-9]+}/enabled")
    public Map<String, Object> enabled(@PathVariable Long id, @RequestBody OnBody b, Authentication auth) {
        return festivals.setEnabled(id, Boolean.TRUE.equals(b.on()), by(auth));
    }

    @DeleteMapping("/{id:[0-9]+}")
    public Map<String, Object> delete(@PathVariable Long id, Authentication auth) {
        festivals.delete(id, by(auth));
        return Map.of("deleted", id);
    }

    @GetMapping("/{id:[0-9]+}/audit")
    public List<Map<String, Object>> audit(@PathVariable Long id) {
        return festivals.auditLog(id);
    }

    /** A picture or animation for the festival (banner, logo, background, popup). Returns the path to keep in the draft and a link to show it. */
    @PostMapping(value = "/assets", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> asset(@RequestParam("file") MultipartFile file) {
        var up = media.upload(file, "festival", AdminMediaService.VISUALS);
        return Map.of("path", up.url(), "url", MediaUrls.link(up.url()), "format", up.format());
    }
}
