package com.panchayat.backend.rooms.controller;

import com.panchayat.backend.rooms.service.RoomExpService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/** A room's level, EXP and what it earned today (for the Room level screen). */
@RestController
@RequestMapping("/api/v1/voice-rooms/{roomId:[0-9]+}/level")
public class RoomLevelController {

    private final RoomExpService exp;

    public RoomLevelController(RoomExpService exp) {
        this.exp = exp;
    }

    @GetMapping
    public Map<String, Object> level(@PathVariable Long roomId) {
        Map<String, Object> m = exp.info(roomId);
        if (m == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found");
        return m;
    }
}
