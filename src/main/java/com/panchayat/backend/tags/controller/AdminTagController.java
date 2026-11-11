package com.panchayat.backend.tags.controller;

import com.panchayat.backend.tags.service.UserTagService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Admin > BFF tags: how many slots people get, the most, and what a slot costs in coins. */
@RestController
@RequestMapping("/api/v1/admin/tags")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
public class AdminTagController {

    private final UserTagService tags;

    public AdminTagController(UserTagService tags) {
        this.tags = tags;
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
