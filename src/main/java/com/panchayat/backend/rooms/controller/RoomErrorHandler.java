package com.panchayat.backend.rooms.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Room-state errors carry short messages written for people ("This seat is
 * taken"), so they are sent as { "message": ... }. Only for this package's
 * controllers; other errors keep Spring Boot's default handling.
 */
@RestControllerAdvice(basePackageClasses = {RoomStateController.class,
        com.panchayat.backend.voice.controller.VoiceRoomController.class})
public class RoomErrorHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handle(ResponseStatusException e) {
        String message = e.getReason() == null ? "That didn't work" : e.getReason();
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("message", message));
    }
}
