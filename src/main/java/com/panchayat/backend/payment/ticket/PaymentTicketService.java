package com.panchayat.backend.payment.ticket;

import com.panchayat.backend.admin.media.AdminMediaService;
import com.panchayat.backend.admin.media.MediaUrls;
import com.panchayat.backend.common.PublicIds;
import com.panchayat.backend.sarpanch.SarpanchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Payment tickets: a person who has a problem with a payment (money taken but no coins, a top-up that wasn't verified, a refund...) raises
 * a ticket. Staff answer from the admin panel; every answer also arrives as a message from Munshi ji (the payments desk). The person can add
 * a follow-up to the ticket; a closed ticket can't be added to (they raise a new one).
 */
@Service
public class PaymentTicketService {

    public static final Set<String> CATEGORIES = Set.of("NOT_CREDITED", "MONEY_DEDUCTED", "MANUAL_TOPUP", "REFUND", "OTHER");
    public static final int MAX_OPEN = 3;

    private final JdbcTemplate jdbc;
    private final AdminMediaService media;
    @Autowired @Lazy private SarpanchService sarpanch;

    public PaymentTicketService(JdbcTemplate jdbc, AdminMediaService media) {
        this.jdbc = jdbc;
        this.media = media;
    }

    /* ---------------- the person's side ---------------- */

    @Transactional
    public Map<String, Object> create(Long userId, String category, String reference, String message, MultipartFile screenshot) {
        String cat = category == null ? "OTHER" : category.trim().toUpperCase(Locale.ROOT);
        if (!CATEGORIES.contains(cat)) throw bad("Choose what went wrong.");
        String text = message == null ? "" : message.strip();
        if (text.length() < 10) throw bad("Tell us what happened (at least 10 letters).");
        if (text.length() > 1000) throw bad("Please keep it under 1000 letters.");
        String ref = clip(reference, 80);
        Long open = jdbc.queryForObject("select count(*) from payment_tickets where user_id = ? and status <> 'CLOSED'", Long.class, userId);
        if (open != null && open >= MAX_OPEN) throw new ResponseStatusException(HttpStatus.CONFLICT, "You already have " + MAX_OPEN + " open tickets. Wait for a reply or add to one of them.");
        String proof = null;
        if (screenshot != null && !screenshot.isEmpty()) proof = media.upload(screenshot, "tickets", AdminMediaService.PICTURES).url();
        Long id = jdbc.queryForObject("insert into payment_tickets (user_id, category, reference, message, proof_path) values (?, ?, ?, ?, ?) returning id", Long.class, userId, cat, ref, text, proof);
        jdbc.update("insert into payment_ticket_messages (ticket_id, sender, body) values (?, 'USER', ?)", id, text);
        sarpanch.sendSafely(userId, new SarpanchService.Message("Ticket #" + id + " received",
                "We got your payment problem" + (ref == null ? "" : " (" + ref + ")") + ". Our team will check it and reply here. You can add more details to the ticket any time.",
                null, "View ticket", "/ticket?id=" + id, "PAYMENT"), "ticket:" + id + ":created");
        return get(userId, id);
    }

    public List<Map<String, Object>> mine(Long userId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> t : jdbc.queryForList("select * from payment_tickets where user_id = ? order by id desc limit 20", userId)) out.add(summary(t));
        return out;
    }

    public Map<String, Object> get(Long userId, long id) {
        List<Map<String, Object>> l = jdbc.queryForList("select * from payment_tickets where id = ? and user_id = ?", id, userId);
        if (l.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That ticket wasn't found.");
        Map<String, Object> out = new LinkedHashMap<>(summary(l.get(0)));
        out.put("messages", thread(id, true));
        return out;
    }

    @Transactional
    public Map<String, Object> userMessage(Long userId, long id, String body) {
        String text = body == null ? "" : body.strip();
        if (text.isEmpty()) throw bad("Write your message.");
        if (text.length() > 1000) throw bad("Please keep it under 1000 letters.");
        List<Map<String, Object>> l = jdbc.queryForList("select status from payment_tickets where id = ? and user_id = ? for update", id, userId);
        if (l.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That ticket wasn't found.");
        if ("CLOSED".equals(l.get(0).get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "This ticket is closed. Please raise a new one.");
        Long recent = jdbc.queryForObject("select count(*) from payment_ticket_messages where ticket_id = ? and sender = 'USER' and created_at > now() - interval '1 hour'", Long.class, id);
        if (recent != null && recent >= 10) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many messages. Please wait a little.");
        jdbc.update("insert into payment_ticket_messages (ticket_id, sender, body) values (?, 'USER', ?)", id, text);
        jdbc.update("update payment_tickets set status = 'OPEN', updated_at = now() where id = ?", id);
        return get(userId, id);
    }

    /* ---------------- staff side (admin panel) ---------------- */

    public Map<String, Object> adminList(String status, String q, int page, int size) {
        int sz = Math.max(1, Math.min(size, 100));
        int pg = Math.max(0, page);
        StringBuilder where = new StringBuilder(" where true");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.equalsIgnoreCase("ALL")) {
            where.append(" and t.status = ?");
            args.add(status.toUpperCase(Locale.ROOT));
        }
        String text = q == null ? "" : q.trim();
        if (!text.isEmpty()) {
            where.append(" and (t.reference ilike ? or u.full_name ilike ? or t.id::text = ?");
            args.add("%" + text + "%");
            args.add("%" + text + "%");
            args.add(text.replace("#", ""));
            Long pub = PublicIds.parse(text);
            if (pub != null) {
                where.append(" or u.public_id = ?");
                args.add(pub);
            }
            where.append(")");
        }
        String from = " from payment_tickets t join users u on u.id = t.user_id";
        Long total = jdbc.queryForObject("select count(*)" + from + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(sz);
        pageArgs.add((long) pg * sz);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> t : jdbc.queryForList("select t.*, u.full_name, u.public_id as user_public_id" + from + where
                + " order by case t.status when 'OPEN' then 0 when 'ANSWERED' then 1 else 2 end, t.updated_at desc limit ? offset ?", pageArgs.toArray())) {
            Map<String, Object> m = new LinkedHashMap<>(summary(t));
            m.put("user", Map.of("id", String.valueOf(t.get("user_id")), "name", String.valueOf(t.get("full_name")), "publicId", t.get("user_public_id") == null ? 0L : ((Number) t.get("user_public_id")).longValue()));
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total == null ? 0 : total);
        out.put("page", pg);
        out.put("size", sz);
        out.put("open", jdbc.queryForObject("select count(*) from payment_tickets where status = 'OPEN'", Long.class));
        out.put("items", items);
        return out;
    }

    public Map<String, Object> adminGet(long id) {
        List<Map<String, Object>> l = jdbc.queryForList("select t.*, u.full_name, u.public_id as user_public_id from payment_tickets t join users u on u.id = t.user_id where t.id = ?", id);
        if (l.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That ticket wasn't found.");
        Map<String, Object> t = l.get(0);
        Map<String, Object> out = new LinkedHashMap<>(summary(t));
        out.put("user", Map.of("id", String.valueOf(t.get("user_id")), "name", String.valueOf(t.get("full_name")), "publicId", t.get("user_public_id") == null ? 0L : ((Number) t.get("user_public_id")).longValue()));
        out.put("proofUrl", MediaUrls.link((String) t.get("proof_path")));
        out.put("messages", thread(id, false));
        return out;
    }

    @Transactional
    public Map<String, Object> reply(long id, String admin, String body) {
        String text = body == null ? "" : body.strip();
        if (text.isEmpty()) throw bad("Write the reply.");
        if (text.length() > 1000) throw bad("The reply can be up to 1000 letters.");
        List<Map<String, Object>> l = jdbc.queryForList("select user_id, status from payment_tickets where id = ? for update", id);
        if (l.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That ticket wasn't found.");
        long userId = ((Number) l.get(0).get("user_id")).longValue();
        Long mid = jdbc.queryForObject("insert into payment_ticket_messages (ticket_id, sender, admin_name, body) values (?, 'ADMIN', ?, ?) returning id", Long.class, id, admin, text);
        jdbc.update("update payment_tickets set status = 'ANSWERED', updated_at = now(), closed_at = null, closed_by = null where id = ?", id);
        sarpanch.sendSafely(userId, new SarpanchService.Message("Reply to your ticket #" + id, text, null, "View ticket", "/ticket?id=" + id, "PAYMENT"), "ticket:" + id + ":reply:" + mid);
        return adminGet(id);
    }

    @Transactional
    public Map<String, Object> close(long id, String admin, String note) {
        List<Map<String, Object>> l = jdbc.queryForList("select user_id, status from payment_tickets where id = ? for update", id);
        if (l.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That ticket wasn't found.");
        if ("CLOSED".equals(l.get(0).get("status"))) return adminGet(id);
        long userId = ((Number) l.get(0).get("user_id")).longValue();
        String why = clip(note, 500);
        if (why != null) jdbc.update("insert into payment_ticket_messages (ticket_id, sender, admin_name, body) values (?, 'ADMIN', ?, ?)", id, admin, why);
        jdbc.update("update payment_tickets set status = 'CLOSED', closed_at = now(), closed_by = ?, updated_at = now() where id = ?", admin, id);
        sarpanch.sendSafely(userId, new SarpanchService.Message("Ticket #" + id + " closed",
                (why == null ? "We have closed your ticket." : why) + " If it isn't solved, please raise a new ticket.", null, "View ticket", "/ticket?id=" + id, "PAYMENT"), "ticket:" + id + ":closed");
        return adminGet(id);
    }

    @Transactional
    public Map<String, Object> reopen(long id) {
        int n = jdbc.update("update payment_tickets set status = 'OPEN', closed_at = null, closed_by = null, updated_at = now() where id = ? and status = 'CLOSED'", id);
        if (n == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a closed ticket can be reopened.");
        return adminGet(id);
    }

    /** A message from Munshi ji to one person (by their public ID), written in the admin panel. */
    public Map<String, Object> messageUser(long publicId, String title, String body) {
        List<Long> ids = jdbc.queryForList("select id from users where public_id = ?", Long.class, publicId);
        if (ids.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No one has that ID.");
        String text = body == null ? "" : body.strip();
        if (text.isEmpty()) throw bad("Write the message.");
        if (text.length() > 1000) throw bad("The message can be up to 1000 letters.");
        boolean sent = sarpanch.send(ids.get(0), new SarpanchService.Message(clip(title, 120), text, null, null, null, "PAYMENT"), null);
        return Map.of("sent", sent);
    }

    /* ---------------- shared ---------------- */

    private Map<String, Object> summary(Map<String, Object> t) {
        Map<String, Object> m = new LinkedHashMap<>();
        long id = ((Number) t.get("id")).longValue();
        m.put("id", String.valueOf(id));
        m.put("category", t.get("category"));
        m.put("reference", t.get("reference"));
        m.put("status", t.get("status"));
        m.put("message", t.get("message"));
        m.put("createdAt", String.valueOf(t.get("created_at")));
        m.put("updatedAt", String.valueOf(t.get("updated_at")));
        return m;
    }

    private List<Map<String, Object>> thread(long ticketId, boolean forPerson) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("select id, sender, admin_name, body, created_at from payment_ticket_messages where ticket_id = ? order by id", ticketId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", String.valueOf(r.get("id")));
            m.put("from", r.get("sender"));
            // The person sees replies as Munshi ji's; staff see which staff member wrote it
            if (!forPerson) m.put("adminName", r.get("admin_name"));
            m.put("body", r.get("body"));
            m.put("createdAt", String.valueOf(r.get("created_at")));
            out.add(m);
        }
        return out;
    }

    private static String clip(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }
}
