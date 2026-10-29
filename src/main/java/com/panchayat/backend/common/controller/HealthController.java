package com.panchayat.backend.common.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

    @GetMapping
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> response = Map.of(
                "status", "UP",
                "service", "panchayat-backend",
                "timestamp", Instant.now().toString()
        );

        return ResponseEntity.status(HttpStatus.OK).body(response);
    }c
}