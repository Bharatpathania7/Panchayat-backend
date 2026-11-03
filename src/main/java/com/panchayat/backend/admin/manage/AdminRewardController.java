package com.panchayat.backend.admin.manage;

import com.panchayat.backend.rewards.service.RewardAdminService;
import com.panchayat.backend.rewards.service.RewardAdminService.ItemInput;
import com.panchayat.backend.rewards.service.RewardAdminService.RuleInput;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * Spend rewards (random and guaranteed) in the admin panel. Super Admins,
 * Admins and Moderators manage them; only a Super Admin can delete a reward.
 */
@RestController
@RequestMapping("/api/v1/admin/manage/rewards")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','MODERATOR')")
public class AdminRewardController {

    private final RewardAdminService service;

    public AdminRewardController(RewardAdminService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return service.list();
    }

    /** Built-in item types plus every custom type already used. */
    @GetMapping("/types")
    public List<String> types() {
        return service.types();
    }

    /** Who won what, newest first. */
    @GetMapping("/grants")
    public Map<String, Object> grants(@RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size,
                                      @RequestParam(required = false) Long userId) {
        return service.grants(page, size, userId);
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody RuleInput body, Authentication auth) {
        return service.createRule(body, auth == null ? null : auth.getName());
    }

    @PatchMapping("/{id:[0-9]+}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody RuleInput body) {
        return service.updateRule(id, body);
    }

    @DeleteMapping("/{id:[0-9]+}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.deleteRule(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id:[0-9]+}/items")
    public Map<String, Object> addItem(@PathVariable Long id, @RequestBody ItemInput body) {
        return service.addItem(id, body);
    }

    @PatchMapping("/items/{itemId:[0-9]+}")
    public Map<String, Object> updateItem(@PathVariable Long itemId, @RequestBody ItemInput body) {
        return service.updateItem(itemId, body);
    }

    @DeleteMapping("/items/{itemId:[0-9]+}")
    public Map<String, Object> deleteItem(@PathVariable Long itemId) {
        return service.deleteItem(itemId);
    }

    /** kind: media (PNG/JPG/JPEG/SVGA/MP4/MP3) or sound (MP3). Field: file. */
    @PostMapping(value = "/items/{itemId:[0-9]+}/{kind:media|sound}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(@PathVariable Long itemId, @PathVariable String kind, @RequestParam("file") MultipartFile file) {
        return service.upload(itemId, kind, file);
    }

    @DeleteMapping("/items/{itemId:[0-9]+}/{kind:media|sound}")
    public Map<String, Object> removeFile(@PathVariable Long itemId, @PathVariable String kind) {
        return service.removeFile(itemId, kind);
    }
}
