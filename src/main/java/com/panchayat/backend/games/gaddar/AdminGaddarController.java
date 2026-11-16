package com.panchayat.backend.games.gaddar;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * How busy Gaon ka Gaddaar is on this server.
 *   GET /api/v1/admin/manage/gaddar/stats?reset=true   games, players, messages a second, and how long a world update takes
 * (a world update must take well under tickBudgetMicros, or the game starts to lag).
 */
@RestController
@RequestMapping("/api/v1/admin/manage/gaddar")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
public class AdminGaddarController {

    private final GaddarService game;

    public AdminGaddarController(GaddarService game) {
        this.game = game;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats(@RequestParam(defaultValue = "false") boolean reset) {
        return game.stats(reset);
    }
}
