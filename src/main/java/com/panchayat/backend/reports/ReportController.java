package com.panchayat.backend.reports;

import com.panchayat.backend.auth.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Reporting.
 *   POST  /api/v1/reports                              a signed-in person reports a user, a chat message or a room
 *   GET   /api/v1/admin/reports?status=OPEN|RESOLVED|DISMISSED
 *   PATCH /api/v1/admin/reports/{id}                   { status }
 */
@RestController
public class ReportController {

    private final ReportService reports;
    private final UserRepository users;

    public ReportController(ReportService reports, UserRepository users) {
        this.reports = reports;
        this.users = users;
    }

    public record ReportBody(String targetType, Long userId, String ref, String reason, String note, String snippet) {
    }

    public record StatusBody(String status) {
    }

    @PostMapping("/api/v1/reports")
    public Map<String, Object> report(@RequestBody ReportBody b, Authentication auth) {
        if (auth == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in first");
        Long me = users.findByEmailIgnoreCase(auth.getName()).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in first")).getId();
        if (b == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a reason.");
        return reports.report(me, b.targetType(), b.userId(), b.ref(), b.reason(), b.note(), b.snippet());
    }

    @GetMapping("/api/v1/admin/reports")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','MODERATOR')")
    public List<Map<String, Object>> list(@RequestParam(required = false) String status) {
        return reports.list(status);
    }

    @GetMapping("/api/v1/admin/reports/open-count")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','MODERATOR')")
    public Map<String, Object> openCount() {
        return Map.of("open", reports.openCount());
    }

    @PatchMapping("/api/v1/admin/reports/{id:[0-9]+}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','MODERATOR')")
    public Map<String, Object> setStatus(@PathVariable Long id, @RequestBody StatusBody b, Authentication auth) {
        return reports.setStatus(id, b == null ? null : b.status(), auth == null ? "admin" : auth.getName());
    }
}
