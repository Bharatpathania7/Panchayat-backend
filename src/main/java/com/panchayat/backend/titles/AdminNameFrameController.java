package com.panchayat.backend.titles;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin > Name frames.
 *   GET    /api/v1/admin/name-frames                      the frames (with how many people hold each)
 *   POST   /api/v1/admin/name-frames                      { name }
 *   PATCH  /api/v1/admin/name-frames/{id}                 { name?, frameScale?, frameY?, plateX/Y/W/H?, textColor?, strokeColor?, active?, sortOrder? }
 *   POST   /api/v1/admin/name-frames/{id}/image           the frame picture (PNG / WEBP / GIF with a see-through middle)
 *   DELETE /api/v1/admin/name-frames/{id}
 *   GET/POST/DELETE /{id}/rules                           top-up rules (TOPUP_TOTAL / TOPUP_ONE, rupees, days)
 *   POST   /{id}/grant { publicId, days }  and  DELETE /{id}/grant/{userId}
 *   GET    /{id}/holders
 */
@RestController
@RequestMapping("/api/v1/admin/name-frames")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
public class AdminNameFrameController {

    private final NameFrameService frames;

    public AdminNameFrameController(NameFrameService frames) {
        this.frames = frames;
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handle(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("message", e.getReason() == null ? "That didn't work" : e.getReason()));
    }

    private static Map<String, Object> view(Map<String, Object> f) {
        Map<String, Object> m = new LinkedHashMap<>(f);
        m.put("imageUrl", NameFrameService.publicUrl((String) f.get("imageUrl")));
        return m;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> f : frames.list()) {
            Map<String, Object> m = view(f);
            m.put("rules", frames.rules(((Number) f.get("id")).longValue()));
            out.add(m);
        }
        return out;
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        return view(frames.create(body == null ? Map.of() : body));
    }

    @PatchMapping("/{id:[0-9]+}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return view(frames.update(id, body == null ? Map.of() : body));
    }

    @PostMapping(value = "/{id:[0-9]+}/image", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> image(@PathVariable Long id, @RequestParam("file") MultipartFile file) {
        return view(frames.upload(id, file));
    }

    @DeleteMapping("/{id:[0-9]+}")
    public Map<String, Object> delete(@PathVariable Long id) {
        return frames.delete(id);
    }

    public record RuleBody(String kind, Long value, Integer days) {
    }

    @PostMapping("/{id:[0-9]+}/rules")
    public Map<String, Object> addRule(@PathVariable Long id, @RequestBody RuleBody b) {
        if (b == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a rule type.");
        return frames.addRule(id, b.kind(), b.value(), b.days());
    }

    @DeleteMapping("/{id:[0-9]+}/rules/{ruleId:[0-9]+}")
    public Map<String, Object> deleteRule(@PathVariable Long id, @PathVariable Long ruleId) {
        frames.deleteRule(id, ruleId);
        return Map.of("deleted", true);
    }

    public record GrantBody(Long userId, Long publicId, Integer days) {
    }

    @PostMapping("/{id:[0-9]+}/grant")
    public Map<String, Object> grant(@PathVariable Long id, @RequestBody GrantBody b) {
        if (b == null || (b.userId() == null && b.publicId() == null)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a user.");
        return frames.grantTo(id, b.userId(), b.publicId(), b.days() == null ? 0 : b.days());
    }

    @DeleteMapping("/{id:[0-9]+}/grant/{userId:[0-9]+}")
    public Map<String, Object> revoke(@PathVariable Long id, @PathVariable Long userId) {
        frames.revoke(userId, id);
        return Map.of("revoked", true);
    }

    @GetMapping("/{id:[0-9]+}/holders")
    public List<Map<String, Object>> holders(@PathVariable Long id) {
        return frames.holders(id);
    }
}
