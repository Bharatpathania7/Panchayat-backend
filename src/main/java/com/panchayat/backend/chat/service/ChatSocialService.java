package com.panchayat.backend.chat.service;

import com.panchayat.backend.auth.entity.User;
import com.panchayat.backend.auth.repository.UserRepository;
import com.panchayat.backend.common.PublicIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chat beyond two-person messages:
 *
 * - Groups: a name, an owner, admins and members. The owner names admins; the
 *   owner and admins add and remove members and rename the group. Anyone can
 *   leave; when the owner leaves, the longest-standing admin (or member) takes over.
 * - Mute: each person can mute a conversation for themselves (no alerts).
 * - Block: a block stops direct messages both ways until it is lifted.
 * - Online: someone is online while the app has its live connection open;
 *   changes go to /topic/presence, and "last seen" is kept when they leave.
 * - Typing: "X is typing" goes to the other members (/user/queue/chat.typing).
 * Group changes go to every member on /user/queue/chat.events so lists update.
 */
@Service
public class ChatSocialService {

    private static final Logger log = LoggerFactory.getLogger(ChatSocialService.class);
    public static final int MAX_GROUP_MEMBERS = 200;

    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final SimpMessagingTemplate messaging;
    private final SimpUserRegistry registry;
    /** Last "typing" signal per user and conversation, to drop floods. */
    private final Map<String, Long> lastTyping = new ConcurrentHashMap<>();

    public ChatSocialService(JdbcTemplate jdbc, UserRepository users, SimpMessagingTemplate messaging, SimpUserRegistry registry) {
        this.jdbc = jdbc;
        this.users = users;
        this.messaging = messaging;
        this.registry = registry;
    }

    /* ---------------- conversations ---------------- */

    public boolean isGroup(Long conversationId) {
        return "GROUP".equals(kind(conversationId));
    }

    public String kind(Long conversationId) {
        List<String> k = jdbc.queryForList("select kind from chat_conversations where id = ?", String.class, conversationId);
        if (k.isEmpty()) throw notFound("That chat doesn't exist any more.");
        return k.get(0);
    }

    /** Your role and mute in a conversation; 404 when you're not in it. */
    private Map<String, Object> membership(Long conversationId, Long userId) {
        List<Map<String, Object>> r = jdbc.queryForList(
                "select role, muted from chat_conversation_participants where conversation_id = ? and user_id = ?", conversationId, userId);
        if (r.isEmpty()) throw notFound("You're not in this chat.");
        return r.get(0);
    }

    /** Blocks and DMs: throws when this message must not be sent. */
    public void checkCanSend(Long conversationId, Long senderId) {
        if (isGroup(conversationId)) return;
        Long other = otherOf(conversationId, senderId);
        if (other == null) return;
        if (blocked(senderId, other)) throw forbidden("You blocked this person. Unblock them to send messages.");
        if (blocked(other, senderId)) throw forbidden("You can't message this person.");
    }

    private Long otherOf(Long conversationId, Long me) {
        List<Long> ids = jdbc.queryForList("select user_id from chat_conversation_participants where conversation_id = ? and user_id <> ?",
                Long.class, conversationId, me);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /* ---------------- groups ---------------- */

    @Transactional
    public Map<String, Object> createGroup(Long creator, String rawName, Collection<Long> memberIds) {
        String name = groupName(rawName);
        LinkedHashSet<Long> members = new LinkedHashSet<>(memberIds == null ? List.of() : memberIds);
        members.remove(creator);
        if (members.isEmpty()) throw bad("Add at least one person to the group.");
        if (members.size() + 1 > MAX_GROUP_MEMBERS) throw bad("A group can have up to " + MAX_GROUP_MEMBERS + " people.");
        requireUsers(members);
        Long id = jdbc.queryForObject(
                "insert into chat_conversations (kind, name, created_by) values ('GROUP', ?, ?) returning id", Long.class, name, creator);
        jdbc.update("insert into chat_conversation_participants (conversation_id, user_id, role) values (?, ?, 'OWNER')", id, creator);
        for (Long m : members) {
            jdbc.update("insert into chat_conversation_participants (conversation_id, user_id, role) values (?, ?, 'MEMBER')", id, m);
        }
        notifyMembers(id, "GROUP_CREATED", creator, null);
        return group(id, creator);
    }

    public Map<String, Object> group(Long conversationId, Long me) {
        Map<String, Object> mine = membership(conversationId, me);
        Map<String, Object> g = jdbc.queryForMap("select id, kind, name, created_by, created_at from chat_conversations where id = ?", conversationId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("conversationId", conversationId);
        out.put("kind", g.get("kind"));
        out.put("name", g.get("name"));
        out.put("myRole", mine.get("role"));
        out.put("muted", mine.get("muted"));
        out.put("members", members(conversationId));
        return out;
    }

    public List<Map<String, Object>> members(Long conversationId, Long me) {
        membership(conversationId, me);
        return members(conversationId);
    }

    private List<Map<String, Object>> members(Long conversationId) {
        Set<String> online = onlineEmails();
        return jdbc.query(
                "select p.user_id, p.role, p.joined_at, u.full_name, u.avatar_url, u.email, u.last_seen_at"
                        + " from chat_conversation_participants p join users u on u.id = p.user_id"
                        + " where p.conversation_id = ?"
                        + " order by case p.role when 'OWNER' then 0 when 'ADMIN' then 1 else 2 end, p.joined_at",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    long uid = rs.getLong("user_id");
                    String email = rs.getString("email");
                    m.put("userId", uid);
                    m.put("publicId", PublicIds.user(uid));
                    m.put("name", rs.getString("full_name"));
                    m.put("avatarUrl", rs.getString("avatar_url"));
                    m.put("role", rs.getString("role"));
                    m.put("online", email != null && online.contains(email.toLowerCase(Locale.ROOT)));
                    m.put("lastSeenAt", ts(rs.getTimestamp("last_seen_at")));
                    return m;
                }, conversationId);
    }

    @Transactional
    public Map<String, Object> addMembers(Long conversationId, Long actor, Collection<Long> userIds) {
        requireGroup(conversationId);
        requireAdmin(conversationId, actor);
        LinkedHashSet<Long> add = new LinkedHashSet<>(userIds == null ? List.of() : userIds);
        add.removeIf(id -> id == null || isMember(conversationId, id));
        if (add.isEmpty()) throw bad("They're already in the group.");
        Long count = jdbc.queryForObject("select count(*) from chat_conversation_participants where conversation_id = ?", Long.class, conversationId);
        if ((count == null ? 0 : count) + add.size() > MAX_GROUP_MEMBERS) throw bad("A group can have up to " + MAX_GROUP_MEMBERS + " people.");
        requireUsers(add);
        for (Long id : add) {
            jdbc.update("insert into chat_conversation_participants (conversation_id, user_id, role) values (?, ?, 'MEMBER')", conversationId, id);
        }
        notifyMembers(conversationId, "MEMBERS_ADDED", actor, add.iterator().next());
        return group(conversationId, actor);
    }

    /** Remove someone (owner/admin), or leave (yourself). */
    @Transactional
    public Map<String, Object> removeMember(Long conversationId, Long actor, Long target) {
        requireGroup(conversationId);
        String actorRole = (String) membership(conversationId, actor).get("role");
        Map<String, Object> t = membership(conversationId, target);
        String targetRole = (String) t.get("role");
        boolean self = actor.equals(target);
        if (!self) {
            boolean ok = "OWNER".equals(actorRole) || ("ADMIN".equals(actorRole) && "MEMBER".equals(targetRole));
            if (!ok) throw forbidden("Only the owner can remove an admin, and only admins can remove members.");
        }
        List<Long> before = memberIds(conversationId);
        jdbc.update("delete from chat_conversation_participants where conversation_id = ? and user_id = ?", conversationId, target);
        List<Long> left = memberIds(conversationId);
        if (left.isEmpty()) {
            jdbc.update("delete from chat_conversations where id = ?", conversationId);
            return Map.of("conversationId", conversationId, "deleted", true);
        }
        if ("OWNER".equals(targetRole)) {
            // The longest-standing admin takes over, else the longest-standing member
            jdbc.update("update chat_conversation_participants set role = 'OWNER' where id = ("
                    + " select id from chat_conversation_participants where conversation_id = ?"
                    + " order by case role when 'ADMIN' then 0 else 1 end, joined_at limit 1)", conversationId);
        }
        notifyUsers(before, conversationId, self ? "MEMBER_LEFT" : "MEMBER_REMOVED", actor, target);
        return self ? Map.of("conversationId", conversationId, "left", true) : group(conversationId, actor);
    }

    @Transactional
    public Map<String, Object> setAdmin(Long conversationId, Long actor, Long target, boolean admin) {
        requireGroup(conversationId);
        if (!"OWNER".equals(membership(conversationId, actor).get("role"))) throw forbidden("Only the group owner can choose admins.");
        if (actor.equals(target)) throw bad("You're the owner already.");
        membership(conversationId, target);
        jdbc.update("update chat_conversation_participants set role = ? where conversation_id = ? and user_id = ?",
                admin ? "ADMIN" : "MEMBER", conversationId, target);
        notifyMembers(conversationId, admin ? "ADMIN_ADDED" : "ADMIN_REMOVED", actor, target);
        return group(conversationId, actor);
    }

    @Transactional
    public Map<String, Object> rename(Long conversationId, Long actor, String rawName) {
        requireGroup(conversationId);
        requireAdmin(conversationId, actor);
        jdbc.update("update chat_conversations set name = ?, updated_at = now() where id = ?", groupName(rawName), conversationId);
        notifyMembers(conversationId, "RENAMED", actor, null);
        return group(conversationId, actor);
    }

    /* ---------------- mute and block ---------------- */

    @Transactional
    public Map<String, Object> setMuted(Long conversationId, Long me, boolean muted) {
        membership(conversationId, me);
        jdbc.update("update chat_conversation_participants set muted = ? where conversation_id = ? and user_id = ?", muted, conversationId, me);
        return Map.of("conversationId", conversationId, "muted", muted);
    }

    @Transactional
    public Map<String, Object> setBlocked(Long me, Long other, boolean block) {
        if (me.equals(other)) throw bad("You can't block yourself.");
        if (users.findById(other).isEmpty()) throw notFound("That person wasn't found.");
        if (block) {
            jdbc.update("insert into user_blocks (blocker_id, blocked_id) values (?, ?) on conflict do nothing", me, other);
        } else {
            jdbc.update("delete from user_blocks where blocker_id = ? and blocked_id = ?", me, other);
        }
        // Their app learns it can't message you (no reason is given to them)
        users.findById(other).ifPresent(u -> send(u.getEmail(), "/queue/chat.events",
                Map.of("type", block ? "BLOCKED" : "UNBLOCKED", "userId", String.valueOf(me))));
        return Map.of("userId", other, "blocked", block);
    }

    public List<Map<String, Object>> blocks(Long me) {
        return jdbc.query("select b.blocked_id, b.created_at, u.full_name, u.avatar_url from user_blocks b join users u on u.id = b.blocked_id"
                        + " where b.blocker_id = ? order by b.created_at desc",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    long uid = rs.getLong("blocked_id");
                    m.put("userId", uid);
                    m.put("publicId", PublicIds.user(uid));
                    m.put("name", rs.getString("full_name"));
                    m.put("avatarUrl", rs.getString("avatar_url"));
                    m.put("blockedAt", ts(rs.getTimestamp("created_at")));
                    return m;
                }, me);
    }

    public boolean blocked(Long blocker, Long blocked) {
        Boolean b = jdbc.queryForObject("select exists (select 1 from user_blocks where blocker_id = ? and blocked_id = ?)", Boolean.class, blocker, blocked);
        return Boolean.TRUE.equals(b);
    }

    /** A new message to everyone in the conversation, the sender too (apps merge it by id). */
    public void deliver(Long conversationId, Object message) {
        for (Long id : memberIds(conversationId)) users.findById(id).ifPresent(u -> send(u.getEmail(), "/queue/chat", message));
    }

    /* ---------------- online and typing ---------------- */

    /** userId -> { online, lastSeenAt } for the ids asked about (up to 200). */
    public Map<String, Object> presence(Collection<Long> ids) {
        List<Long> list = ids == null ? List.of() : ids.stream().filter(Objects::nonNull).distinct().limit(200).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        if (list.isEmpty()) return out;
        Set<String> online = onlineEmails();
        String in = String.join(",", Collections.nCopies(list.size(), "?"));
        jdbc.query("select id, email, last_seen_at from users where id in (" + in + ")", rs -> {
            String email = rs.getString("email");
            out.put(String.valueOf(rs.getLong("id")), Map.of(
                    "online", email != null && online.contains(email.toLowerCase(Locale.ROOT)),
                    "lastSeenAt", Optional.ofNullable(ts(rs.getTimestamp("last_seen_at"))).map(Object::toString).orElse("")));
        }, list.toArray());
        return out;
    }

    /** "X is typing" to the other members. At most one signal per second per person and chat. */
    public void typing(Long conversationId, User who, boolean typing) {
        if (!isMember(conversationId, who.getId())) return;
        String key = who.getId() + ":" + conversationId;
        long now = System.currentTimeMillis();
        Long last = lastTyping.get(key);
        if (typing && last != null && now - last < 1000) return;
        lastTyping.put(key, now);
        if (!isGroup(conversationId)) {
            Long other = otherOf(conversationId, who.getId());
            if (other != null && (blocked(other, who.getId()) || blocked(who.getId(), other))) return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("conversationId", String.valueOf(conversationId));
        body.put("userId", String.valueOf(who.getId()));
        body.put("name", who.getFullName());
        body.put("typing", typing);
        for (Long id : memberIds(conversationId)) {
            if (id.equals(who.getId())) continue;
            users.findById(id).ifPresent(u -> send(u.getEmail(), "/queue/chat.typing", body));
        }
    }

    @EventListener
    public void onConnected(SessionConnectedEvent e) {
        presenceChanged(e.getUser(), true);
    }

    @EventListener
    public void onDisconnected(SessionDisconnectEvent e) {
        presenceChanged(e.getUser(), false);
    }

    private void presenceChanged(Principal p, boolean connected) {
        if (p == null || p.getName() == null || !p.getName().contains("@")) return;
        try {
            users.findByEmailIgnoreCase(p.getName()).ifPresent(u -> {
                // Another tab or phone may still be connected
                boolean online = connected || onlineEmails().contains(p.getName().toLowerCase(Locale.ROOT));
                Instant now = Instant.now();
                if (!online) jdbc.update("update users set last_seen_at = ? where id = ?", Timestamp.from(now), u.getId());
                Object payload = Map.of("userId", String.valueOf(u.getId()), "online", online, "lastSeenAt", now.toString());
                messaging.convertAndSend("/topic/presence", payload);
            });
        } catch (Exception ex) {
            log.debug("Presence update failed: {}", ex.getMessage());
        }
    }

    /* ---------------- internals ---------------- */

    private void requireGroup(Long conversationId) {
        if (!isGroup(conversationId)) throw bad("This is a direct chat, not a group.");
    }

    private void requireAdmin(Long conversationId, Long actor) {
        String role = (String) membership(conversationId, actor).get("role");
        if (!"OWNER".equals(role) && !"ADMIN".equals(role)) throw forbidden("Only the group owner and admins can do this.");
    }

    private boolean isMember(Long conversationId, Long userId) {
        Boolean b = jdbc.queryForObject("select exists (select 1 from chat_conversation_participants where conversation_id = ? and user_id = ?)",
                Boolean.class, conversationId, userId);
        return Boolean.TRUE.equals(b);
    }

    private List<Long> memberIds(Long conversationId) {
        return jdbc.queryForList("select user_id from chat_conversation_participants where conversation_id = ?", Long.class, conversationId);
    }

    private void requireUsers(Collection<Long> ids) {
        for (Long id : ids) if (id == null || users.findById(id).isEmpty()) throw bad("Someone you added wasn't found.");
    }

    private void notifyMembers(Long conversationId, String type, Long actor, Long target) {
        notifyUsers(memberIds(conversationId), conversationId, type, actor, target);
    }

    private void notifyUsers(Collection<Long> ids, Long conversationId, String type, Long actor, Long target) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("conversationId", String.valueOf(conversationId));
        body.put("actorId", actor == null ? null : String.valueOf(actor));
        body.put("targetId", target == null ? null : String.valueOf(target));
        for (Long id : ids) users.findById(id).ifPresent(u -> send(u.getEmail(), "/queue/chat.events", body));
    }

    private void send(String email, String dest, Object body) {
        if (email == null || email.isBlank()) return;
        try {
            messaging.convertAndSendToUser(email, dest, body);
        } catch (Exception e) {
            log.debug("Chat push failed: {}", e.getMessage());
        }
    }

    public Set<String> onlineEmails() {
        Set<String> out = new HashSet<>();
        registry.getUsers().forEach(u -> {
            if (u.getName() != null) out.add(u.getName().toLowerCase(Locale.ROOT));
        });
        return out;
    }

    private static String groupName(String raw) {
        String n = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
        if (n.isEmpty()) throw bad("Give the group a name.");
        if (n.length() > 60) throw bad("Group names can be up to 60 characters.");
        return n;
    }

    private static Instant ts(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }

    private static ResponseStatusException forbidden(String m) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, m);
    }

    private static ResponseStatusException notFound(String m) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, m);
    }
}
