package com.panchayat.backend.tags.controller;

import com.panchayat.backend.tags.service.TagTypeService;
import com.panchayat.backend.tags.service.UserTagService;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Admin > BFF tags: how many slots people get, the most, and what a slot costs in coins. */
@RestController
@RequestMapping("/api/v1/admin/tags")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
public class AdminTagController {

    private final UserTagService tags;
    private final TagTypeService types;

    public AdminTagController(UserTagService tags, TagTypeService types) {
        this.tags = tags;
        this.types = types;
    }

    @GetMapping("/types")
    public List<Map<String, Object>> typeList() {
        return types.listAll();
    }

    @PostMapping("/types")
    public Map<String, Object> typeAdd(@RequestBody Map<String, Object> body) {
        return types.create(body == null ? Map.of() : body);
    }

    @PatchMapping("/types/{id}")
    public Map<String, Object> typeEdit(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return types.update(id, body == null ? Map.of() : body);
    }

    @DeleteMapping("/types/{id}")
    public Map<String, Object> typeDelete(@PathVariable String id) {
        return types.delete(id);
    }

    @PostMapping("/types/{id}/upload/{kind}")
    public Map<String, Object> typeUpload(@PathVariable String id, @PathVariable String kind, @RequestParam("file") MultipartFile file) {
        return types.upload(id, kind, file);
    }

    @DeleteMapping("/types/{id}/{kind}")
    public Map<String, Object> typeClear(@PathVariable String id, @PathVariable String kind) {
        return types.clear(id, kind);
    }

    @GetMapping("/settings")
    public Map<String, Object> settings() {
        return tags.rulesMap();
    }

    @PutMapping("/settings")
    public Map<String, Object> save(@RequestBody Map<String, Object> body) {
        return tags.saveRules(body == null ? Map.of() : body);
    }
}
