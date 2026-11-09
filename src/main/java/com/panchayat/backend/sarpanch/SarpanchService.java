package com.panchayat.backend.sarpanch;

import com.panchayat.backend.admin.media.MediaUrls;
import com.panchayat.backend.chat.encryption.ChatEncryptionService;
import com.panchayat.backend.progress.service.ExpService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sarpanch: Panchayat's official account (a real user row with official = true).
 *
 *  - Messages live in the normal chat tables, in one pinned, read-only
 *    "system" conversation per user (made on the first message), with type
 *    SYSTEM and extra fields (title, imageUrl, actionLabel, actionUrl, category).
 *  - Only the server writes them: automatic ones from features (notify with a
 *    template and an eventKey, sent at most once per event) and the team's
 *    messages and broadcasts from the admin panel.
 *  - Each new message counts as unread and is pushed to the person on
 *    /user/queue/chat (the same place private chat messages go).
 *  - Broadcasts go out in batches from a background job, never in a request.
 */
@Service
public class SarpanchService {

    private static final Logger log = LoggerFactory.getLogger(SarpanchService.class);
    public static final Set<String> CATEGORIES = Set.of("PAYMENT", "VIP", "RED_PACKET", "REWARD", "CP", "SAFETY", "ANNOUNCEMENT", "OTHER");
    public static final int BATCH = 500;
    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZONE);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z]+)}");

    private final JdbcTemplate jdbc;
    private final ChatEncryptionService encryption;
    private final SimpMessagingTemplate messaging;
    private final TransactionTemplate tx;
    private final TransactionTemplate txNew;
    private final ObjectMapper json = new ObjectMapper();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sarpanch-worker");
        t.setDaemon(true);
        return t;
    });
    private volatile Long sarpanchId;

    public SarpanchService(JdbcTemplate jdbc, ChatEncryptionService encryption, SimpMessagingTemplate messaging, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.encryption = encryption;
        this.messaging = messaging;
        this.tx = new TransactionTemplate(txm);
        this.txNew = new TransactionTemplate(txm);
        this.txNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        worker.scheduleWithFixedDelay(this::runBroadcasts, 5, 3, TimeUnit.SECONDS);
        worker.scheduleWithFixedDelay(this::vipExpiringJob, 2, 60, TimeUnit.MINUTES);
    }

    @PreDestroy
    void stop() {
        worker.shutdownNow();
    }

    /* ================= the account ================= */

    public Long sarpanchId() {
        Long id = sarpanchId;
        if (id == null) {
            List<Long> l = jdbc.queryForList("select id from users where official order by id limit 1", Long.class);
            if (l.isEmpty()) throw new IllegalStateException("The Sarpanch account is missing (migration V41).");
            id = l.get(0);
            sarpanchId = id;
        }
        return id;
    }

    public boolean isSarpanch(Long userId) {
        return userId != null && userId.equals(sarpanchId());
    }

    public boolean isSystemConversation(Long conversationId) {
        Boolean b = jdbc.query("select system_conversation from chat_conversations where id = ?", rs -> rs.next() ? rs.getBoolean(1) : Boolean.FALSE, conversationId);
        return Boolean.TRUE.equals(b);
    }

    public Map<String, Object> profile() {
        Map<String, Object> u = jdbc.queryForMap("select id, full_name, avatar_url, bio, updated_at from users where id = ?", sarpanchId());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", String.valueOf(u.get("id")));
        m.put("name", u.get("full_name"));
        m.put("handle", "@sarpanch");
        m.put("avatarUrl", MediaUrls.link((String) u.get("avatar_url")));
        m.put("bio", u.get("bio"));
        m.put("official", true);
        m.put("updatedAt", u.get("updated_at") == null ? null : u.get("updated_at").toString());
        return m;
    }

    @Transactional
    public Map<String, Object> saveProfile(String name, String bio, String avatarUrl) {
        String n = name == null ? "" : name.strip();
        if (n.isEmpty() || n.length() > 40) throw bad("The name must be 1 to 40 characters.");
        if (bio != null && bio.length() > 160) throw bad("The bio can be up to 160 characters.");
        jdbc.update("update users set full_name = ?, bio = ?, avatar_url = coalesce(?, avatar_url), updated_at = now() where id = ?", n, bio, avatarUrl, sarpanchId());
        return profile();
    }

    /* ================= sending ================= */

    public record Message(String title, String body, String imageUrl, String actionLabel, String actionUrl, String category) {
    }

    /**
     * Send one message from Sarpanch to one person. With an eventKey, a second
     * send of the same event does nothing (returns false). Joins the caller's
     * transaction, so it's saved together with the change it reports.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public boolean send(Long userId, Message m, String eventKey) {
        if (userId == null || isSarpanch(userId)) return false;
        String body = m.body() == null ? "" : m.body().strip();
        if (body.isEmpty()) throw bad("The message is empty.");
        if (body.length() > 1000) throw bad("Message is too long.");
        String category = m.category() != null && CATEGORIES.contains(m.category()) ? m.category() : "OTHER";
        if (eventKey != null) {
            Integer seen = jdbc.queryForObject("select count(*) from chat_messages where event_key = ?", Integer.class, eventKey);
            if (seen != null && seen > 0) return false;
        }
        String email = jdbc.query("select email from users where id = ?", rs -> rs.next() ? rs.getString(1) : null, userId);
        if (email == null) return false;
        Long conv = conversationFor(userId);
        var enc = encryption.encrypt(body);
        Long id;
        try {
            id = jdbc.queryForObject(
                    "insert into chat_messages (conversation_id, sender_user_id, ciphertext, nonce, encryption_key_version, created_at, message_type, title, image_url, action_label, action_url, event_key, category)"
                            + " values (?, ?, ?, ?, ?, now(), 'SYSTEM', ?, ?, ?, ?, ?, ?) returning id",
                    Long.class, conv, sarpanchId(), enc.ciphertext(), enc.nonce(), enc.keyVersion(),
                    cut(m.title(), 120), cut(m.imageUrl(), 500), cut(m.actionLabel(), 40), cut(m.actionUrl(), 300), eventKey, category);
        } catch (DuplicateKeyException twice) {
            return false; // the same event at the same moment: sent once
        }
        jdbc.update("insert into chat_message_receipts (message_id, user_id) values (?, ?)", id, userId);
        jdbc.update("update chat_conversations set updated_at = now() where id = ?", conv);
        Map<String, Object> payload = view(id, conv, body, m.title(), m.imageUrl(), m.actionLabel(), m.actionUrl(), category, Instant.now());
        afterCommit(() -> {
            try {
                messaging.convertAndSendToUser(email, "/queue/chat", (Object) payload);
            } catch (Exception e) {
                log.debug("Sarpanch message not pushed: {}", e.getMessage());
            }
        });
        return true;
    }

    /** An automatic message from a template ("Payment successful … {coins} …"), at most once per eventKey. */
    @Transactional(propagation = Propagation.REQUIRED)
    public boolean notify(Long userId, String templateKey, Map<String, ?> params, String eventKey) {
        List<Map<String, Object>> t = jdbc.queryForList("select * from sarpanch_templates where key = ?", templateKey);
        if (t.isEmpty()) {
            log.warn("Sarpanch template {} is missing", templateKey);
            return false;
        }
        Map<String, Object> row = t.get(0);
        return send(userId, new Message(render((String) row.get("title"), params), render((String) row.get("body"), params), null,
                (String) row.get("action_label"), (String) row.get("action_url"), (String) row.get("category")), eventKey);
    }

    /** notify() that never breaks the caller: a missing template or a hiccup is logged, not thrown. */
    public void notifySafely(Long userId, String templateKey, Map<String, ?> params, String eventKey) {
        Map<String, Object> copy = params == null ? Map.of() : new HashMap<>(params);
        // After the event is final (the caller committed), in a transaction of its own:
        // a failed message never rolls back the payment / VIP / CP it reports
        afterCommit(() -> {
            try {
                txNew.executeWithoutResult(s -> notify(userId, templateKey, copy, eventKey));
            } catch (Exception e) {
                log.warn("Sarpanch {} not sent: {}", templateKey, e.getMessage());
            }
        });
    }

    /** send() that never breaks the caller and is saved in a transaction of its own, after the caller committed. */
    public void sendSafely(Long userId, Message m, String eventKey) {
        afterCommit(() -> {
            try {
                txNew.executeWithoutResult(s -> send(userId, m, eventKey));
            } catch (Exception e) {
                log.warn("Sarpanch message not sent: {}", e.getMessage());
            }
        });
    }

    public static String formatDate(Instant at) {
        return at == null ? "" : DATE.format(at);
    }

    static String render(String text, Map<String, ?> params) {
        if (text == null) return null;
        Matcher mt = PLACEHOLDER.matcher(text);
        StringBuilder b = new StringBuilder();
        while (mt.find()) {
            Object v = params == null ? null : params.get(mt.group(1));
            mt.appendReplacement(b, Matcher.quoteReplacement(v == null ? "" : String.valueOf(v)));
        }
        mt.appendTail(b);
        return b.toString();
    }

    /** The person's read-only conversation with Sarpanch (made the first time they open it or get a message). */
    @Transactional(propagation = Propagation.REQUIRED)
    public Long systemConversationFor(Long userId) {
        return conversationFor(userId);
    }

    /** The person's conversation with Sarpanch (made the first time). */
    Long conversationFor(Long userId) {
        List<Long> l = jdbc.queryForList(
                "select c.id from chat_conversations c join chat_conversation_participants p on p.conversation_id = c.id"
                        + " where c.system_conversation and p.user_id = ? order by c.id limit 1", Long.class, userId);
        if (!l.isEmpty()) return l.get(0);
        // One per person even when two messages race: lock the person's row
        jdbc.queryForList("select id from users where id = ? for no key update", userId);
        l = jdbc.queryForList(
                "select c.id from chat_conversations c join chat_conversation_participants p on p.conversation_id = c.id"
                        + " where c.system_conversation and p.user_id = ? order by c.id limit 1", Long.class, userId);
        if (!l.isEmpty()) return l.get(0);
        Long id = jdbc.queryForObject("insert into chat_conversations (created_at, updated_at, kind, system_conversation, pinned) values (now(), now(), 'DIRECT', true, true) returning id", Long.class);
        jdbc.update("insert into chat_conversation_participants (conversation_id, user_id) values (?, ?), (?, ?)", id, sarpanchId(), id, userId);
        return id;
    }

    /** A SYSTEM message as the app reads it (ChatMessageResponse plus the extra fields). */
    static Map<String, Object> view(Long id, Long conv, String body, String title, String imageUrl, String actionLabel, String actionUrl, String category, Instant at) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("conversationId", conv);
        m.put("senderUserId", null);
        m.put("messageType", "SYSTEM");
        m.put("message", body);
        m.put("imageUrl", MediaUrls.link(imageUrl));
        m.put("title", title);
        m.put("actionLabel", actionLabel);
        m.put("actionUrl", actionUrl);
        m.put("category", category);
        m.put("official", true);
        m.put("createdAt", at.toString());
        return m;
    }

    /** The extra fields of SYSTEM messages, by message id (for chat history). */
    public Map<Long, Map<String, Object>> extras(Collection<Long> messageIds) {
        Map<Long, Map<String, Object>> out = new HashMap<>();
        if (messageIds == null || messageIds.isEmpty()) return out;
        String in = String.join(",", Collections.nCopies(messageIds.size(), "?"));
        jdbc.query("select id, title, image_url, action_label, action_url, category from chat_messages where id in (" + in + ") and message_type = 'SYSTEM'", rs -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("title", rs.getString("title"));
            m.put("imageUrl", MediaUrls.link(rs.getString("image_url")));
            m.put("actionLabel", rs.getString("action_label"));
            m.put("actionUrl", rs.getString("action_url"));
            m.put("category", rs.getString("category"));
            out.put(rs.getLong("id"), m);
        }, messageIds.toArray());
        return out;
    }

    /** Your Sarpanch messages only (a Notifications screen), newest first, optionally one category. */
    @Transactional(readOnly = true)
    public Map<String, Object> myMessages(Long userId, String category, int page, int size) {
        int sz = Math.max(1, Math.min(size, 50));
        int pg = Math.max(0, page);
        List<Object> args = new ArrayList<>(List.of(userId));
        String extra = "";
        if (category != null && !category.isBlank()) {
            extra = " and m.category = ?";
            args.add(category.toUpperCase(Locale.ROOT));
        }
        args.add(sz);
        args.add(pg * sz);
        List<Map<String, Object>> items = new ArrayList<>();
        jdbc.query("select m.id, m.conversation_id, m.ciphertext, m.nonce, m.encryption_key_version, m.title, m.image_url, m.action_label, m.action_url, m.category, m.created_at,"
                        + " (select r.read_at from chat_message_receipts r where r.message_id = m.id and r.user_id = p.user_id limit 1) as read_at"
                        + " from chat_messages m join chat_conversations c on c.id = m.conversation_id and c.system_conversation"
                        + " join chat_conversation_participants p on p.conversation_id = c.id and p.user_id = ?"
                        + " where m.message_type = 'SYSTEM' and m.deleted_at is null" + extra + " order by m.created_at desc, m.id desc limit ? offset ?",
                rs -> {
                    String body = encryption.decrypt(rs.getString("ciphertext"), rs.getString("nonce"), rs.getInt("encryption_key_version"));
                    Map<String, Object> v = view(rs.getLong("id"), rs.getLong("conversation_id"), body, rs.getString("title"), rs.getString("image_url"),
                            rs.getString("action_label"), rs.getString("action_url"), rs.getString("category"), rs.getTimestamp("created_at").toInstant());
                    v.put("read", rs.getTimestamp("read_at") != null);
                    items.add(v);
                }, args.toArray());
        return Map.of("items", items, "page", pg, "size", sz);
    }

    /** The newest announcement sent to everyone (for Discover). */
    @Transactional(readOnly = true)
    public Map<String, Object> latestAnnouncement() {
        List<Map<String, Object>> l = jdbc.queryForList(
                "select id, title, body, image_url, action_label, action_url, category, finished_at, created_at from sarpanch_broadcasts"
                        + " where status = 'SENT' and audience like '%\"ALL\"%' order by coalesce(finished_at, created_at) desc limit 1");
        if (l.isEmpty()) return null;
        Map<String, Object> b = l.get(0);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(b.get("id")));
        m.put("title", b.get("title"));
        m.put("body", b.get("body"));
        m.put("imageUrl", MediaUrls.link((String) b.get("image_url")));
        m.put("actionLabel", b.get("action_label"));
        m.put("actionUrl", b.get("action_url"));
        m.put("category", b.get("category"));
        Object at = b.get("finished_at") != null ? b.get("finished_at") : b.get("created_at");
        m.put("at", ((Timestamp) at).toInstant().toString());
        m.put("sarpanch", profile());
        return m;
    }

    /* ================= admin: messages and broadcasts ================= */

    /** Send now to specific people. Returns { sent, skipped }. */
    @Transactional
    public Map<String, Object> sendToUsers(List<Long> userIds, Message m) {
        if (userIds == null || userIds.isEmpty()) throw bad("Audience is empty.");
        if (userIds.size() > 1000) throw bad("Up to 1000 people at a time. Use a broadcast for more.");
        validate(m);
        int sent = 0, skipped = 0;
        for (Long given : new LinkedHashSet<>(userIds)) {
            // The ID shown in the app (public ID) or the internal ID
            Long id = given == null ? null : jdbc.query("select id from users where public_id = ? or display_public_id = ? or id = ? order by (id = ?) limit 1",
                    rs -> rs.next() ? rs.getLong(1) : null, given, given, given, given);
            Integer ok = id == null ? 0 : jdbc.queryForObject("select count(*) from users where id = ? and not official", Integer.class, id);
            if (ok == null || ok == 0) {
                skipped++;
                continue;
            }
            if (send(id, m, null)) sent++;
            else skipped++;
        }
        return Map.of("sent", sent, "skipped", skipped);
    }

    @Transactional
    public Map<String, Object> createBroadcast(Message m, Map<String, Object> audience, Instant scheduledAt, String by) {
        validate(m);
        Map<String, Object> aud = audience == null ? Map.of("type", "ALL") : audience;
        String type = String.valueOf(aud.getOrDefault("type", "")).toUpperCase(Locale.ROOT);
        if (!Set.of("ALL", "USERS", "FILTER").contains(type)) throw bad("Audience type must be ALL, USERS or FILTER.");
        if ("USERS".equals(type) && (!(aud.get("userIds") instanceof List<?> l) || l.isEmpty())) throw bad("Audience is empty.");
        Map<String, Object> clean = new LinkedHashMap<>(aud);
        clean.put("type", type);
        if (countAudience(clean) == 0) throw bad("Audience is empty.");
        Long id = jdbc.queryForObject(
                "insert into sarpanch_broadcasts (title, body, image_url, action_label, action_url, category, audience, scheduled_at, status, created_by)"
                        + " values (?, ?, ?, ?, ?, ?, ?, ?, 'SCHEDULED', ?) returning id",
                Long.class, cut(m.title(), 120), m.body().strip(), cut(m.imageUrl(), 500), cut(m.actionLabel(), 40), cut(m.actionUrl(), 300),
                m.category() != null && CATEGORIES.contains(m.category()) ? m.category() : "ANNOUNCEMENT",
                json.writeValueAsString(clean), scheduledAt == null ? null : Timestamp.from(scheduledAt), by);
        return broadcast(id);
    }

    @Transactional
    public Map<String, Object> cancelBroadcast(Long id) {
        int n = jdbc.update("update sarpanch_broadcasts set status = 'CANCELLED', finished_at = now() where id = ? and status in ('DRAFT', 'SCHEDULED', 'SENDING')", id);
        if (n == 0) {
            Integer exists = jdbc.queryForObject("select count(*) from sarpanch_broadcasts where id = ?", Integer.class, id);
            if (exists == null || exists == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That broadcast doesn't exist.");
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That broadcast has already finished.");
        }
        return broadcast(id);
    }

    public Map<String, Object> listBroadcasts(int page, int size) {
        int sz = Math.max(1, Math.min(size, 100));
        List<Long> ids = jdbc.queryForList("select id from sarpanch_broadcasts order by id desc limit ? offset ?", Long.class, sz, Math.max(0, page) * sz);
        Long total = jdbc.queryForObject("select count(*) from sarpanch_broadcasts", Long.class);
        return Map.of("items", ids.stream().map(this::broadcast).toList(), "total", total == null ? 0 : total, "page", page, "size", sz);
    }

    Map<String, Object> broadcast(Long id) {
        Map<String, Object> b = jdbc.queryForMap("select * from sarpanch_broadcasts where id = ?", id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(id));
        m.put("title", b.get("title"));
        m.put("body", b.get("body"));
        m.put("imageUrl", MediaUrls.link((String) b.get("image_url")));
        m.put("actionLabel", b.get("action_label"));
        m.put("actionUrl", b.get("action_url"));
        m.put("category", b.get("category"));
        try {
            m.put("audience", json.readValue((String) b.get("audience"), Map.class));
        } catch (RuntimeException e) {
            m.put("audience", Map.of());
        }
        m.put("scheduledAt", b.get("scheduled_at") == null ? null : ((Timestamp) b.get("scheduled_at")).toInstant().toString());
        m.put("status", b.get("status"));
        m.put("createdBy", b.get("created_by"));
        m.put("createdAt", ((Timestamp) b.get("created_at")).toInstant().toString());
        m.put("sentCount", b.get("sent_count"));
        m.put("finishedAt", b.get("finished_at") == null ? null : ((Timestamp) b.get("finished_at")).toInstant().toString());
        return m;
    }

    /** The background job: due broadcasts go out BATCH people at a time. */
    void runBroadcasts() {
        try {
            List<Long> due = jdbc.queryForList(
                    "select id from sarpanch_broadcasts where status in ('SCHEDULED', 'SENDING') and (scheduled_at is null or scheduled_at <= now()) order by id limit 3", Long.class);
            for (Long id : due) {
                boolean more = true;
                for (int i = 0; more && i < 20; i++) more = Boolean.TRUE.equals(tx.execute(s -> sendBatch(id)));
            }
        } catch (Exception e) {
            log.warn("Sarpanch broadcast run failed: {}", e.getMessage());
        }
    }

    /** One batch of one broadcast. Returns true while there's more to send. */
    boolean sendBatch(Long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("select * from sarpanch_broadcasts where id = ? and status in ('SCHEDULED', 'SENDING') for update", id);
        if (rows.isEmpty()) return false; // cancelled or finished
        Map<String, Object> b = rows.get(0);
        jdbc.update("update sarpanch_broadcasts set status = 'SENDING' where id = ? and status = 'SCHEDULED'", id);
        Map<String, Object> aud;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> a = json.readValue((String) b.get("audience"), Map.class);
            aud = a;
        } catch (RuntimeException e) {
            aud = Map.of("type", "ALL");
        }
        long after = ((Number) b.get("last_user_id")).longValue();
        Audience q = audienceSql(aud);
        List<Object> args = new ArrayList<>(q.args);
        args.add(after);
        args.add(BATCH);
        List<Long> users = jdbc.queryForList("select u.id from users u where " + q.where + " and u.id > ? order by u.id limit ?", Long.class, args.toArray());
        Message m = new Message((String) b.get("title"), (String) b.get("body"), (String) b.get("image_url"), (String) b.get("action_label"), (String) b.get("action_url"), (String) b.get("category"));
        int sent = 0;
        for (Long u : users) if (send(u, m, "broadcast:" + id + ":" + u)) sent++;
        if (users.isEmpty()) {
            jdbc.update("update sarpanch_broadcasts set status = 'SENT', finished_at = now() where id = ?", id);
            return false;
        }
        jdbc.update("update sarpanch_broadcasts set last_user_id = ?, sent_count = sent_count + ? where id = ?", users.get(users.size() - 1), sent, id);
        return users.size() == BATCH;
    }

    record Audience(String where, List<Object> args) {
    }

    /** SQL for an audience: ALL, USERS { userIds }, FILTER { vipOnly, minLevel, joinedAfter, activeInLastDays }. */
    Audience audienceSql(Map<String, Object> aud) {
        StringBuilder w = new StringBuilder("not u.official");
        List<Object> args = new ArrayList<>();
        String type = String.valueOf(aud.getOrDefault("type", "ALL")).toUpperCase(Locale.ROOT);
        if ("USERS".equals(type)) {
            List<Long> ids = new ArrayList<>();
            if (aud.get("userIds") instanceof List<?> l) for (Object o : l) {
                try {
                    ids.add(Long.valueOf(String.valueOf(o)));
                } catch (NumberFormatException ignored) {
                    // not a user id
                }
            }
            if (ids.isEmpty()) return new Audience("false", List.of());
            w.append(" and u.id in (").append(String.join(",", Collections.nCopies(ids.size(), "?"))).append(")");
            args.addAll(ids);
        } else if ("FILTER".equals(type)) {
            if (Boolean.TRUE.equals(aud.get("vipOnly")) || "true".equals(String.valueOf(aud.get("vipOnly"))))
                w.append(" and u.vip_active and (u.vip_expires_at is null or u.vip_expires_at > now())");
            if (aud.get("minLevel") != null) {
                int lvl = Integer.parseInt(String.valueOf(aud.get("minLevel")));
                long need = 0;
                for (int k = 1; k < lvl; k++) need += ExpService.toNext(k);
                w.append(" and u.exp >= ?");
                args.add(need);
            }
            if (aud.get("joinedAfter") != null) {
                w.append(" and u.created_at >= ?");
                args.add(Timestamp.from(LocalDate.parse(String.valueOf(aud.get("joinedAfter")).substring(0, 10)).atStartOfDay(ZONE).toInstant()));
            }
            if (aud.get("activeInLastDays") != null) {
                w.append(" and coalesce(u.last_seen_at, u.updated_at) >= now() - make_interval(days => ?)");
                args.add(Integer.parseInt(String.valueOf(aud.get("activeInLastDays"))));
            }
            // "country" isn't stored on accounts yet, so it can't narrow the audience
        }
        return new Audience(w.toString(), args);
    }

    long countAudience(Map<String, Object> aud) {
        Audience q = audienceSql(aud);
        Long n = jdbc.queryForObject("select count(*) from users u where " + q.where, Long.class, q.args.toArray());
        return n == null ? 0 : n;
    }

    /* ================= templates ================= */

    public List<Map<String, Object>> templates() {
        return jdbc.queryForList("select key, title, body, action_label as \"actionLabel\", action_url as \"actionUrl\", category, updated_by as \"updatedBy\", updated_at as \"updatedAt\" from sarpanch_templates order by key");
    }

    @Transactional
    public Map<String, Object> saveTemplate(String key, Map<String, Object> body, String by) {
        String text = body == null ? null : (String) body.get("body");
        if (text == null || text.isBlank()) throw bad("The text can't be empty.");
        if (text.length() > 1000) throw bad("Message is too long.");
        String category = String.valueOf(body.getOrDefault("category", "OTHER"));
        if (!CATEGORIES.contains(category)) throw bad("Unknown category.");
        int n = jdbc.update("update sarpanch_templates set title = ?, body = ?, action_label = ?, action_url = ?, category = ?, updated_by = ?, updated_at = now() where key = ?",
                cut((String) body.get("title"), 120), text.strip(), cut((String) body.get("actionLabel"), 40), cut((String) body.get("actionUrl"), 300), category, by, key);
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That template doesn't exist.");
        return jdbc.queryForMap("select key, title, body, action_label as \"actionLabel\", action_url as \"actionUrl\", category, updated_by as \"updatedBy\", updated_at as \"updatedAt\" from sarpanch_templates where key = ?", key);
    }

    /* ================= VIP ending soon (daily) ================= */

    void vipExpiringJob() {
        try {
            List<Map<String, Object>> soon = jdbc.queryForList(
                    "select id, vip_expires_at from users where vip_active and vip_expires_at between now() + interval '2 days' and now() + interval '3 days' and not official");
            for (Map<String, Object> u : soon) {
                Instant at = ((Timestamp) u.get("vip_expires_at")).toInstant();
                long id = ((Number) u.get("id")).longValue();
                tx.executeWithoutResult(s -> notifySafely(id, "VIP_EXPIRING", Map.of("date", formatDate(at)), "vip-expiring:" + id + ":" + at.getEpochSecond()));
            }
        } catch (Exception e) {
            log.warn("VIP expiry reminders failed: {}", e.getMessage());
        }
    }

    /* ================= internals ================= */

    private static void validate(Message m) {
        if (m == null || m.body() == null || m.body().isBlank()) throw bad("Write a message.");
        if (m.body().length() > 1000) throw bad("Message is too long.");
        if (m.title() != null && m.title().length() > 120) throw bad("The title can be up to 120 characters.");
        if (m.actionUrl() != null && !m.actionUrl().isBlank() && !m.actionUrl().matches("^(/[A-Za-z0-9/_?=&.-]*|panchayat://[A-Za-z0-9/_?=&.-]*)$"))
            throw bad("The action link must be an in-app link like /wallet or panchayat://wallet.");
    }

    private static String cut(String s, int max) {
        if (s == null) return null;
        String t = s.strip();
        if (t.isEmpty()) return null;
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }

    private static void afterCommit(Runnable r) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    r.run();
                }
            });
        } else {
            r.run();
        }
    }
}
