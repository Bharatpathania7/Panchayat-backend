package com.panchayat.backend.gifts.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Gift categories, managed in the admin panel. A category's kind decides who can send its gifts:
 * NORMAL = anyone; VIP = people with active VIP; CP = only to your CP partner.
 */
@Service
public class GiftCategoryService {

    private final JdbcTemplate jdbc;

    public GiftCategoryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> list() {
        return jdbc.queryForList("select name, kind, sort_order as \"sortOrder\" from gift_categories order by sort_order, name");
    }

    /** The category's stored spelling (case doesn't matter), or an error naming the ones that exist. */
    public String canonical(String c) {
        String v = c == null ? "" : c.trim();
        List<String> found = jdbc.queryForList("select name from gift_categories where lower(name) = lower(?)", String.class, v);
        if (!found.isEmpty()) return found.get(0);
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pick one of the categories: " + String.join(", ", jdbc.queryForList("select name from gift_categories order by sort_order, name", String.class)) + ".");
    }

    public Map<String, Object> create(String name, String kind) {
        String n = name == null ? "" : name.trim().replaceAll("\\s+", " ");
        if (n.length() < 2 || n.length() > 30) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give the category a name of 2 to 30 letters.");
        String k = kind == null || kind.isBlank() ? "NORMAL" : kind.trim().toUpperCase(Locale.ROOT);
        if (!k.equals("NORMAL") && !k.equals("VIP") && !k.equals("CP")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Kind must be NORMAL, VIP or CP.");
        Integer clash = jdbc.queryForObject("select count(*) from gift_categories where lower(name) = lower(?)", Integer.class, n);
        if (clash != null && clash > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "That category already exists.");
        Integer next = jdbc.queryForObject("select coalesce(max(sort_order), 0) + 1 from gift_categories", Integer.class);
        jdbc.update("insert into gift_categories (name, kind, sort_order) values (?, ?, ?)", n, k, next);
        return Map.of("name", n, "kind", k, "sortOrder", next == null ? 0 : next);
    }

    public void delete(String name) {
        Integer used = jdbc.queryForObject("select count(*) from gifts where lower(category) = lower(?)", Integer.class, name);
        if (used != null && used > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Move its " + used + " gift(s) to another category first.");
        if (jdbc.update("delete from gift_categories where lower(name) = lower(?)", name) == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That category doesn't exist.");
    }

    /** Refuses a send the gift's category doesn't allow. */
    public void check(String giftId, Long senderId, Long receiverId) {
        String kind = jdbc.query("select c.kind from gifts g join gift_categories c on lower(c.name) = lower(g.category) where g.id = ?", rs -> rs.next() ? rs.getString(1) : "NORMAL", giftId);
        if ("VIP".equals(kind)) {
            Boolean vip = jdbc.query("select vip_active and vip_expires_at > now() from users where id = ?", rs -> rs.next() ? rs.getBoolean(1) : false, senderId);
            if (!Boolean.TRUE.equals(vip)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This gift is for VIP members. Become VIP to send it.");
        } else if ("CP".equals(kind)) {
            Integer partner = jdbc.queryForObject("select count(*) from cp_members a join cp_members b on b.couple_id = a.couple_id where a.user_id = ? and b.user_id = ? and a.user_id <> b.user_id",
                    Integer.class, senderId, receiverId);
            if (partner == null || partner == 0) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This is a CP gift. You can send it only to your CP partner.");
        }
    }
}
