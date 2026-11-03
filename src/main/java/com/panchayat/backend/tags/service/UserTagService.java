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

    public static final int TAG_SLOTS = 6;
    public static final Set<String> RELATIONS = Set.of("brosis", "besties", "bro", "confidant", "apprentice", "sibling");

    private final UserTagRepository tags;

    public UserTagService(UserTagRepository tags) {
        this.tags = tags;
    }

    /** What happened when a BFF round closed. */
    public enum FormResult { NEW, EXISTING, FULL }

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
            existing.get().addIntimacy(volume);
            tags.save(existing.get());
            return FormResult.EXISTING;
        }
        if (tags.countFor(winner) >= TAG_SLOTS || tags.countFor(item) >= TAG_SLOTS) return FormResult.FULL;
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
        return m;
    }
}
