package com.panchayat.backend.social.moments;

import com.panchayat.backend.auth.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Moments (signed in):
 *   POST   /api/v1/moments                     multipart: body, visibility (PUBLIC|FRIENDS), files[] (≤9 photos or 1 video)
 *   GET    /api/v1/moments?scope=all|friends|user&userId=&before=&limit=   { items[], next }
 *   DELETE /api/v1/moments/{id}
 *   POST   /api/v1/moments/{id}/like  { on }   → { liked, likeCount }
 *   GET    /api/v1/moments/{id}/comments?after=
 *   POST   /api/v1/moments/{id}/comments { body, replyTo }
 *   DELETE /api/v1/moments/{id}/comments/{commentId}
 */
@RestController
@RequestMapping("/api/v1/moments")
public class MomentController {

    private final MomentService moments;
    private final UserRepository users;

    public MomentController(MomentService moments, UserRepository users) {
        this.moments = moments;
        this.users = users;
    }

    public record LikeRequest(Boolean on) {
    }

    public record CommentRequest(String body, Long replyTo) {
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> post(@RequestParam(value = "body", required = false) String body,
                                    @RequestParam(value = "visibility", required = false) String visibility,
                                    @RequestParam(value = "files", required = false) List<MultipartFile> files,
                                    Authentication auth) {
        return moments.post(me(auth), body, visibility, files);
    }

    @GetMapping
    public Map<String, Object> feed(@RequestParam(defaultValue = "all") String scope, @RequestParam(required = false) Long userId,
                                    @RequestParam(required = false) Long before, @RequestParam(defaultValue = "15") int limit,
                                    Authentication auth) {
        return moments.feed(me(auth), scope, userId, before, limit);
    }

    @DeleteMapping("/{id:[0-9]+}")
    public ResponseEntity<Void> delete(@PathVariable Long id, Authentication auth) {
        moments.delete(me(auth), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id:[0-9]+}/like")
    public Map<String, Object> like(@PathVariable Long id, @RequestBody(required = false) LikeRequest body, Authentication auth) {
        return moments.like(me(auth), id, body == null || body.on() == null || body.on());
    }

    @GetMapping("/{id:[0-9]+}/comments")
    public List<Map<String, Object>> comments(@PathVariable Long id, @RequestParam(required = false) Long after,
                                              @RequestParam(defaultValue = "50") int limit, Authentication auth) {
        me(auth);
        return moments.comments(id, after, limit, null);
    }

    @PostMapping("/{id:[0-9]+}/comments")
    public Map<String, Object> comment(@PathVariable Long id, @RequestBody CommentRequest body, Authentication auth) {
        if (body == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Write a comment.");
        return moments.comment(me(auth), id, body.body(), body.replyTo());
    }

    @DeleteMapping("/{id:[0-9]+}/comments/{commentId:[0-9]+}")
    public ResponseEntity<Void> deleteComment(@PathVariable Long id, @PathVariable Long commentId, Authentication auth) {
        moments.deleteComment(me(auth), id, commentId);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handle(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("message", e.getReason() == null ? "That didn't work" : e.getReason()));
    }

    private Long me(Authentication auth) {
        if (auth == null || auth.getName() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in again.");
        return users.findByEmailIgnoreCase(auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Only user accounts can post Moments.")).getId();
    }
}
