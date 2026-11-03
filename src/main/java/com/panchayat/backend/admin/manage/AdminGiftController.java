package com.panchayat.backend.admin.manage;

import com.panchayat.backend.admin.manage.AdminGiftService.GiftInput;
import com.panchayat.backend.gifts.entity.GiftDefinition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Gifts in the admin panel. Super Admins, Admins and Moderators manage them
 * (the management role group); only a Super Admin can delete one.
 */
@RestController
@RequestMapping("/api/v1/admin/manage/gifts")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','MODERATOR')")
public class AdminGiftController {

    private final AdminGiftService service;

    public AdminGiftController(AdminGiftService service) {
        this.service = service;
    }

    @GetMapping
    public List<GiftDefinition> list() {
        return service.list();
    }

    @PostMapping
    public GiftDefinition create(@RequestBody GiftInput body) {
        return service.create(body);
    }

    @PatchMapping("/{id}")
    public GiftDefinition update(@PathVariable String id, @RequestBody GiftInput body) {
        return service.update(id, body);
    }

    /** kind: image (PNG/JPG/JPEG), animation (SVGA/MP4), sound (MP3). Field: file. */
    @PostMapping(value = "/{id}/{kind:image|animation|sound}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public GiftDefinition upload(@PathVariable String id, @PathVariable String kind, @RequestParam("file") MultipartFile file) {
        return service.upload(id, kind, file);
    }

    @DeleteMapping("/{id}/{kind:animation|sound}")
    public GiftDefinition removeFile(@PathVariable String id, @PathVariable String kind) {
        return service.removeFile(id, kind);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
