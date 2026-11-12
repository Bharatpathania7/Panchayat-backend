package com.panchayat.backend.music;

import com.panchayat.backend.auth.entity.User;
import com.panchayat.backend.auth.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Room music for the app: the library of uploaded songs, uploading, and searching Audius / Jamendo.
 *   GET    /api/v1/music/songs
 *   POST   /api/v1/music/songs   (multipart: file, title, artist, duration, rights)
 *   DELETE /api/v1/music/songs/{id}
 *   GET    /api/v1/music/search?source=audius|jamendo&q=
 * Admin: GET /api/v1/admin/music/songs, PUT .../{id}/hide|show, DELETE .../{id}
 */
@RestController
public class MusicController {

    private final MusicService music;
    private final UserRepository users;

    public MusicController(MusicService music, UserRepository users) {
        this.music = music;
        this.users = users;
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handle(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("message", e.getReason() == null ? "That didn't work" : e.getReason()));
    }

    private Long me(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null || "anonymousUser".equals(auth.getPrincipal())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        Object p = auth.getPrincipal();
        if (p instanceof User u && u.getId() != null) return u.getId();
        return users.findByEmailIgnoreCase(auth.getName()).map(User::getId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Only user accounts can use music"));
    }

    @GetMapping("/api/v1/music/songs")
    public List<Map<String, Object>> songs(Authentication auth) {
        return music.songs(me(auth));
    }

    @PostMapping(value = "/api/v1/music/songs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file, @RequestParam("title") String title,
                                      @RequestParam(value = "artist", required = false) String artist,
                                      @RequestParam(value = "duration", required = false) Integer duration,
                                      @RequestParam(value = "rights", defaultValue = "false") boolean rights, Authentication auth) {
        return music.upload(me(auth), file, title, artist, duration, rights);
    }

    @DeleteMapping("/api/v1/music/songs/{id:[0-9]+}")
    public Map<String, Object> delete(@PathVariable Long id, Authentication auth) {
        music.delete(id, me(auth), false);
        return Map.of("deleted", id);
    }

    @GetMapping("/api/v1/music/search")
    public Map<String, Object> search(@RequestParam(defaultValue = "audius") String source, @RequestParam(defaultValue = "") String q, Authentication auth) {
        me(auth);
        return Map.of("source", source, "results", music.search(source, q), "jamendo", music.jamendoOn());
    }

    @GetMapping("/api/v1/music/sources")
    public Map<String, Object> sources(Authentication auth) {
        me(auth);
        return Map.of("audius", true, "jamendo", music.jamendoOn());
    }

    /* ----------------------------- admin ----------------------------- */

    @GetMapping("/api/v1/admin/music/songs")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','MODERATOR')")
    public List<Map<String, Object>> adminSongs() {
        return music.adminList();
    }

    @PutMapping("/api/v1/admin/music/songs/{id:[0-9]+}/{state:hide|show}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','MODERATOR')")
    public Map<String, Object> adminState(@PathVariable Long id, @PathVariable String state) {
        music.adminSetStatus(id, "hide".equals(state) ? "HIDDEN" : "LIVE");
        return Map.of("id", id, "status", "hide".equals(state) ? "HIDDEN" : "LIVE");
    }

    @DeleteMapping("/api/v1/admin/music/songs/{id:[0-9]+}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public Map<String, Object> adminDelete(@PathVariable Long id) {
        music.delete(id, null, true);
        return Map.of("deleted", id);
    }
}
