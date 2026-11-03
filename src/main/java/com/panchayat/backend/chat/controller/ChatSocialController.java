package com.panchayat.backend.chat.controller;

import com.panchayat.backend.auth.repository.UserRepository;
import com.panchayat.backend.chat.service.ChatSocialService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Groups, members and admins, mute, block and online status.
 *
 *   POST   /api/v1/chat/groups                                   { name, memberIds }
 *   GET    /api/v1/chat/conversations/{id}/info                  group name, your role, members (online)
 *   PATCH  /api/v1/chat/conversations/{id}                       { name }            owner/admins
 *   POST   /api/v1/chat/conversations/{id}/members               { userIds }         owner/admins
 *   DELETE /api/v1/chat/conversations/{id}/members/{userId}      remove (owner/admins) or leave (yourself)
 *   PUT    /api/v1/chat/conversations/{id}/admins/{userId}       { on }              owner
 *   PUT    /api/v1/chat/conversations/{id}/mute                  { on }              yourself
 *   GET    /api/v1/chat/blocks · PUT|DELETE /api/v1/chat/blocks/{userId}
 *   GET    /api/v1/presence?ids=1,2,3
 */
@RestController
public class ChatSocialController {

    private final ChatSocialService social;
    private final UserRepository users;

    public ChatSocialController(ChatSocialService social, UserRepository users) {
        this.social = social;
        this.users = users;
    }

    public record CreateGroup(String name, List<Long> memberIds) {
    }

    public record Rename(String name) {
    }

    public record Members(List<Long> userIds) {
    }

    public record OnOff(Boolean on) {
        boolean value() {
            if (on == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "on is required");
            return on;
        }
    }

    @PostMapping("/api/v1/chat/groups")
    public Map<String, Object> create(@RequestBody CreateGroup body, Authentication auth) {
        return social.createGroup(me(auth), body == null ? null : body.name(), body == null ? null : body.memberIds());
    }

    @GetMapping("/api/v1/chat/conversations/{id:[0-9]+}/info")
    public Map<String, Object> info(@PathVariable Long id, Authentication auth) {
        return social.group(id, me(auth));
    }

    @PatchMapping("/api/v1/chat/conversations/{id:[0-9]+}")
    public Map<String, Object> rename(@PathVariable Long id, @RequestBody Rename body, Authentication auth) {
        return social.rename(id, me(auth), body == null ? null : body.name());
    }

    @PostMapping("/api/v1/chat/conversations/{id:[0-9]+}/members")
    public Map<String, Object> add(@PathVariable Long id, @RequestBody Members body, Authentication auth) {
        return social.addMembers(id, me(auth), body == null ? null : body.userIds());
    }

    @DeleteMapping("/api/v1/chat/conversations/{id:[0-9]+}/members/{userId:[0-9]+}")
    public Map<String, Object> remove(@PathVariable Long id, @PathVariable Long userId, Authentication auth) {
        return social.removeMember(id, me(auth), userId);
    }

    @PutMapping("/api/v1/chat/conversations/{id:[0-9]+}/admins/{userId:[0-9]+}")
    public Map<String, Object> admin(@PathVariable Long id, @PathVariable Long userId, @RequestBody OnOff body, Authentication auth) {
        return social.setAdmin(id, me(auth), userId, body.value());
    }

    @PutMapping("/api/v1/chat/conversations/{id:[0-9]+}/mute")
    public Map<String, Object> mute(@PathVariable Long id, @RequestBody OnOff body, Authentication auth) {
        return social.setMuted(id, me(auth), body.value());
    }

    @GetMapping("/api/v1/chat/blocks")
    public List<Map<String, Object>> blocks(Authentication auth) {
        return social.blocks(me(auth));
    }

    @PutMapping("/api/v1/chat/blocks/{userId:[0-9]+}")
    public Map<String, Object> block(@PathVariable Long userId, Authentication auth) {
        return social.setBlocked(me(auth), userId, true);
    }

    @DeleteMapping("/api/v1/chat/blocks/{userId:[0-9]+}")
    public Map<String, Object> unblock(@PathVariable Long userId, Authentication auth) {
        return social.setBlocked(me(auth), userId, false);
    }

    @GetMapping("/api/v1/presence")
    public Map<String, Object> presence(@RequestParam(defaultValue = "") String ids, Authentication auth) {
        me(auth);
        List<Long> list = Arrays.stream(ids.split(",")).map(String::trim).filter(s -> s.matches("\\d{1,18}")).map(Long::parseLong).toList();
        return social.presence(list);
    }

    private Long me(Authentication auth) {
        if (auth == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in first");
        return users.findByEmailIgnoreCase(auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in first"))
                .getId();
    }
}
