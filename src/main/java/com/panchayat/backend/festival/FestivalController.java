package com.panchayat.backend.festival;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/** What the user app asks at start: the festival that is on now (the published version only, never a draft). Public, so the login screen can be themed too. */
@RestController
@RequestMapping("/api/v1/festival")
public class FestivalController {

    private final FestivalService festivals;

    public FestivalController(FestivalService festivals) {
        this.festivals = festivals;
    }

    @GetMapping("/active")
    public ResponseEntity<Map<String, Object>> active() {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(30)).cachePublic()).body(festivals.active());
    }
}
