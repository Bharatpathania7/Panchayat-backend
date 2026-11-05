package com.panchayat.backend.tags.service;

import com.panchayat.backend.tags.entity.UserTag;
import com.panchayat.backend.tags.repository.UserTagRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * BFF tags: formed by winning a BFF Kharidari, grown by gifts between the two.
 * Each person can hold at most TAG_SLOTS tags. The same rules as the app
 * (lib/app/tags.ts): token by winning gift volume, 1 intimacy per coin.
 */
@Service
public class UserTagService {

    /** Everyone gets BASE_SLOTS; each VIP level adds one, up to MAX_SLOTS. */
    public static final int BASE_SLOTS = 5;
    public static final int MAX_SLOTS = 12;
    public static final Set<String> THEMES = Set.of("rose", "gold", "ocean", "violet", "emerald", "sunset", "midnight");
    public static final Set<String> RELATIONS = Set.of("brosis", "besties", "bro", "confidant", "apprentice", "sibling");

    private final UserTagRepository tags;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public UserTagService(UserTagRepository tags, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.tags = tags;
        this.jdbc = jdbc;
    }

    /** How many BFF tags this person can hold: 5, plus 1 per VIP level while VIP, up to 12. */
    public int slotsFor(Long userId) {
        Integer vip = jdbc.queryForObject(
                "select case when vip_active and (vip_expires_at is null or vip_expires_at > now()) then coalesce(vip_level, 0) else 0 end from users where id = ?",
                Integer.class, userId);
        return Math.min(MAX_SLOTS, BASE_SLOTS + Math.max(0, vip == null ? 0 : vip));
    }

    public Map<String, Object> slots(Long userId) {
        return Map.of("used", tags.countFor(userId), "slots", slotsFor(userId), "max", MAX_SLOTS);
    }

    /** Either person can change the relation, the pet name and the theme of their tag. */
    @Transactional
    public Map<String, Object> customize(Long tagId, Long userId, String relation, String nickname, String theme) {
        UserTag t = tags.findById(tagId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "That tag doesn't exist"));
        if (!t.involves(userId)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "That isn't your tag");
        if (relation != null) {
            if (!RELATIONS.contains(relation)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pick one of the tag types");
            // One tag of each type per person
            if (!relation.equals(t.getRelation())
                    && (tags.countOfType(t.getUserA(), relation) > 0 || tags.countOfType(t.getUserB(), relation) > 0)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "One of you already has a " + label(relation) + " tag. Each person can have one tag of each type.");
            }
            t.setRelation(relation);
        }
        if (nickname != null) {
            String n = nickname.trim().replaceAll("\\s+", " ");
            if (n.length() > 24) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pet names can be up to 24 characters");
            t.setNickname(n.isEmpty() ? null : n);
        }
        if (theme != null) {
            if (!theme.isEmpty() && !THEMES.contains(theme)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pick one of the themes");
            t.setTheme(theme.isEmpty() ? null : theme);
        }
        return toMap(tags.save(t));
    }

    /** What happened when a BFF round closed. */
    public enum FormResult { NEW, EXISTING, FULL, TAKEN }

    /** Does this person already hold a tag of this type (with anyone)? */
    @Transactional(readOnly = true)
    public boolean hasType(Long userId, String relation) {
        return tags.countOfType(userId, relation) > 0;
    }

    public static String label(String relation) {
        return switch (relation == null ? "" : relation) {
            case "brosis" -> "Bro and sis";
            case "besties" -> "Besties";
            case "bro" -> "Bro";
            case "confidant" -> "Confidant";
            case "apprentice" -> "Apprentice";
            case "sibling" -> "Sibling";
            default -> "this";
        };
    }

    public static String tokenFor(long giftVolume) {
        if (giftVolume >= 5000) return "cottage";
        if (giftVolume >= 1000) return "scooter";
        return "bicycle";
    }

    /** The winner and the person on the auction seat become a tag (or grow the one they have). */
    @Transactional
    public FormResult form(Long winner, Long item, String relation, long volume, String roomName, String declaration) {
        if (winner.equals(item)) throw new IllegalArgumentException("A tag needs two people");
        String rel = RELATIONS.contains(relation) ? relation : "besties";
        Optional<UserTag> existing = tags.findPairForUpdate(Math.min(winner, item), Math.max(winner, item));
        if (existing.isPresent()) {
            // The bidding gifts were already counted as intimacy when they were sent (RoomGiftService)
            return FormResult.EXISTING;
        }
        if (tags.countFor(winner) >= slotsFor(winner) || tags.countFor(item) >= slotsFor(item)) return FormResult.FULL;
        // One tag of each type per person
        if (tags.countOfType(winner, rel) > 0 || tags.countOfType(item, rel) > 0) return FormResult.TAKEN;
        try {
            String decl = declaration == null || declaration.isBlank() ? null : declaration.trim();
            if (decl != null && decl.length() > 80) decl = decl.substring(0, 80);
            String room = roomName == null ? null : roomName.length() > 100 ? roomName.substring(0, 100) : roomName;
            tags.saveAndFlush(new UserTag(winner, item, rel, tokenFor(volume), volume, room, decl));
            return FormResult.NEW;
        } catch (DataIntegrityViolationException raced) {
            return FormResult.EXISTING;
        }
    }

    /** A gift between two tagged people adds its coin value to their intimacy. */
    @Transactional
    public void addIntimacy(Long from, Long to, long coins) {
        if (from.equals(to) || coins <= 0) return;
        tags.findPairForUpdate(Math.min(from, to), Math.max(from, to)).ifPresent(t -> {
            t.addIntimacy(coins);
            tags.save(t);
        });
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(Long userId) {
        return tags.findAllFor(userId).stream().map(UserTagService::toMap).toList();
    }

    /** Either person can end their tag. */
    @Transactional
    public void breakTag(Long tagId, Long userId) {
        UserTag t = tags.findById(tagId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "That tag doesn't exist"));
        if (!t.involves(userId)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "That isn't your tag");
        tags.delete(t);
    }

    private static Map<String, Object> toMap(UserTag t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(t.getId()));
        m.put("a", String.valueOf(t.getUserA()));
        m.put("b", String.valueOf(t.getUserB()));
        m.put("relation", t.getRelation());
        m.put("token", t.getToken());
        m.put("intimacy", t.getIntimacy());
        m.put("roomName", t.getRoomName());
        m.put("declaration", t.getDeclaration());
        m.put("createdAt", t.getCreatedAt());
        m.put("nickname", t.getNickname());
        m.put("theme", t.getTheme());
        return m;
    }
}
