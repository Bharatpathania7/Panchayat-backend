package com.panchayat.backend.chat.controller;

import com.panchayat.backend.auth.repository.UserRepository;
import com.panchayat.backend.chat.service.ChatSocialService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Cleaning up chats.
 *
 *   DELETE /api/v1/chat/conversations/{id}/messages/{messageId}        unsend: your own message goes for everyone
 *   POST   /api/v1/chat/conversations/{id}/messages/{messageId}/hide   delete a message for yourself only
 *   POST   /api/v1/chat/conversations/{id}/clear                       delete this chat for yourself
 *   POST   /api/v1/chat/conversations/clear-all                        delete all your chats for yourself
 */
@RestController
public class ChatDeleteController {

    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final ChatSocialService social;

    public ChatDeleteController(JdbcTemplate jdbc, UserRepository users, ChatSocialService social) {
        this.jdbc = jdbc;
        this.users = users;
        this.social = social;
    }

    @DeleteMapping("/api/v1/chat/conversations/{id:[0-9]+}/messages/{messageId:[0-9]+}")
    @Transactional
    public Map<String, Object> unsend(@PathVariable Long id, @PathVariable Long messageId, Authentication auth) {
        Long me = me(auth);
        member(id, me);
        int n = jdbc.update("update chat_messages set deleted_at = now() where id = ? and conversation_id = ? and sender_user_id = ? and deleted_at is null", messageId, id, me);
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "You can only unsend your own messages.");
        social.messageDeleted(id, messageId);
        return Map.of("ok", true);
    }

    @PostMapping("/api/v1/chat/conversations/{id:[0-9]+}/messages/{messageId:[0-9]+}/hide")
    @Transactional
    public Map<String, Object> hide(@PathVariable Long id, @PathVariable Long messageId, Authentication auth) {
        Long me = me(auth);
        member(id, me);
        Integer exists = jdbc.queryForObject("select count(*) from chat_messages where id = ? and conversation_id = ?", Integer.class, messageId, id);
        if (exists == null || exists == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Message not found.");
        jdbc.update("insert into chat_message_hidden (message_id, user_id) values (?, ?) on conflict do nothing", messageId, me);
        return Map.of("ok", true);
    }

    @PostMapping("/api/v1/chat/conversations/{id:[0-9]+}/clear")
    @Transactional
    public Map<String, Object> clear(@PathVariable Long id, Authentication auth) {
        Long me = me(auth);
        member(id, me);
        clearFor(me, id);
        return Map.of("ok", true);
    }

    @PostMapping("/api/v1/chat/conversations/clear-all")
    @Transactional
    public Map<String, Object> clearAll(Authentication auth) {
        Long me = me(auth);
        // The official Sarpanch chat stays
        var ids = jdbc.queryForList(
                "select p.conversation_id from chat_conversation_participants p join chat_conversations c on c.id = p.conversation_id where p.user_id = ? and coalesce(c.system_conversation, false) = false",
                Long.class, me);
        for (Long id : ids) clearFor(me, id);
        return Map.of("ok", true, "cleared", ids.size());
    }

    private void clearFor(Long me, Long conversationId) {
        jdbc.update("update chat_conversation_participants set cleared_at = now() where conversation_id = ? and user_id = ?", conversationId, me);
        // What was deleted no longer counts as unread
        jdbc.update("update chat_message_receipts r set read_at = coalesce(r.read_at, now()) from chat_messages m where m.id = r.message_id and m.conversation_id = ? and r.user_id = ?", conversationId, me);
    }

    private void member(Long conversationId, Long me) {
        Integer n = jdbc.queryForObject("select count(*) from chat_conversation_participants where conversation_id = ? and user_id = ?", Integer.class, conversationId, me);
        if (n == null || n == 0) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You are not in this chat.");
    }

    private Long me(Authentication auth) {
        if (auth == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in first");
        return users.findByEmailIgnoreCase(auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in first"))
                .getId();
    }
}
