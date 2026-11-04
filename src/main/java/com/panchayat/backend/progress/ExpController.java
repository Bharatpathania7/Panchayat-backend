package com.panchayat.backend.progress;

import com.panchayat.backend.auth.repository.UserRepository;
import com.panchayat.backend.progress.service.ExpService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Active Level (signed in):
 *   GET  /api/v1/users/me/level          { exp, level, intoLevel, toNext, today{source:{got,cap}} }
 *   GET  /api/v1/users/{id}/level        { level }
 *   POST /api/v1/users/me/exp/task       { taskId }  50 EXP for a claimed daily task (each task once a day, cap 250)
 */
@RestController
public class ExpController {

    private static final Pattern TASK_ID = Pattern.compile("^[a-z0-9-]{1,40}$");
    private final ExpService exp;
    private final UserRepository users;
    private final JdbcTemplate jdbc;

    public ExpController(ExpService exp, UserRepository users, JdbcTemplate jdbc) {
        this.exp = exp;
        this.users = users;
        this.jdbc = jdbc;
    }

    public record TaskRequest(String taskId) {
    }

    @GetMapping("/api/v1/users/me/level")
    public Map<String, Object> mine(Authentication auth) {
        return exp.view(me(auth));
    }

    @GetMapping("/api/v1/users/{id:[0-9]+}/level")
    public Map<String, Object> of(@PathVariable Long id) {
        Map<String, Object> v = exp.view(id);
        return Map.of("level", v.get("level"));
    }

    @PostMapping("/api/v1/users/me/exp/task")
    public Map<String, Object> task(@RequestBody TaskRequest body, Authentication auth) {
        Long me = me(auth);
        String id = body == null || body.taskId() == null ? "" : body.taskId().trim().toLowerCase();
        if (!TASK_ID.matcher(id).matches()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown task.");
        // Each task counts once a day
        LocalDate day = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        int fresh = jdbc.update("insert into user_exp_daily (user_id, day, source, amount) values (?, ?, ?, 0) on conflict do nothing",
                me, day, ("T:" + id).substring(0, Math.min(12, id.length() + 2)));
        int got = fresh > 0 ? exp.add(me, "TASK", ExpService.TASK) : 0;
        Map<String, Object> v = exp.view(me);
        v.put("added", got);
        return v;
    }

    private Long me(Authentication auth) {
        if (auth == null || auth.getName() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in again.");
        return users.findByEmailIgnoreCase(auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Only user accounts have a level.")).getId();
    }
}
