package com.panchayat.backend.progress.tasks;

import com.panchayat.backend.auth.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Daily tasks.
 *   GET  /api/v1/tasks/daily                    today's tasks with your progress
 *   POST /api/v1/tasks/daily/{id}/claim         claim a finished task: { coins, exp, balance }
 * Admin (ADMIN / SUPER_ADMIN):
 *   GET    /api/v1/admin/daily-tasks
 *   POST   /api/v1/admin/daily-tasks            { title, kind, goal, rewardCoins, rewardExp, active, sortOrder }
 *   PUT    /api/v1/admin/daily-tasks/{id}
 *   DELETE /api/v1/admin/daily-tasks/{id}
 */
@RestController
public class DailyTaskController {

    private final DailyTaskService tasks;
    private final UserRepository users;

    public DailyTaskController(DailyTaskService tasks, UserRepository users) {
        this.tasks = tasks;
        this.users = users;
    }

    @GetMapping("/api/v1/tasks/daily")
    public Map<String, Object> list(Authentication auth) {
        return tasks.list(me(auth));
    }

    @PostMapping("/api/v1/tasks/daily/{id:[0-9]+}/claim")
    public Map<String, Object> claim(@PathVariable Long id, Authentication auth) {
        return tasks.claim(me(auth), id);
    }

    @GetMapping("/api/v1/admin/daily-tasks")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public List<Map<String, Object>> all() {
        return tasks.all();
    }

    @PostMapping("/api/v1/admin/daily-tasks")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        return tasks.save(null, body == null ? Map.of() : body);
    }

    @PutMapping("/api/v1/admin/daily-tasks/{id:[0-9]+}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return tasks.save(id, body == null ? Map.of() : body);
    }

    @DeleteMapping("/api/v1/admin/daily-tasks/{id:[0-9]+}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        tasks.delete(id);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handle(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("message", e.getReason() == null ? "That didn't work" : e.getReason()));
    }

    private Long me(Authentication auth) {
        if (auth == null || auth.getName() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in again.");
        return users.findByEmailIgnoreCase(auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Sign in with a user account.")).getId();
    }
}
