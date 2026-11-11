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
    private final com.panchayat.backend.wallet.service.CoinWalletService wallet;
    private final com.panchayat.backend.wallet.service.WalletPushService walletPush;

    public UserTagService(UserTagRepository tags, org.springframework.jdbc.core.JdbcTemplate jdbc,
                          com.panchayat.backend.wallet.service.CoinWalletService wallet, com.panchayat.backend.wallet.service.WalletPushService walletPush) {
        this.tags = tags;
        this.jdbc = jdbc;
        this.wallet = wallet;
        this.walletPush = walletPush;
    }

    /** The admin's slot rules: how many everyone starts with, the most, what a coin slot costs, and whether VIP levels add slots. */
    public record SlotRules(int base, int max, long price, boolean vipBonus) {
    }

    public SlotRules rules() {
        SlotRules r = jdbc.query("select base_slots, max_slots, slot_price, vip_bonus from tag_settings where id = 1",
                rs -> rs.next() ? new SlotRules(rs.getInt(1), rs.getInt(2), rs.getLong(3), rs.getBoolean(4)) : null);
        return r != null ? r : new SlotRules(BASE_SLOTS, MAX_SLOTS, 5000, true);
    }

    public Map<String, Object> rulesMap() {
        SlotRules r = rules();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("baseSlots", r.base());
        m.put("maxSlots", r.max());
        m.put("slotPrice", r.price());
        m.put("vipBonus", r.vipBonus());
        return m;
    }

    @Transactional
    public Map<String, Object> saveRules(Map<String, Object> in) {
        SlotRules c = rules();
        int base = in.get("baseSlots") instanceof Number n ? n.intValue() : c.base();
        int max = in.get("maxSlots") instanceof Number n ? n.intValue() : c.max();
        long price = in.get("slotPrice") instanceof Number n ? n.longValue() : c.price();
        boolean vip = in.get("vipBonus") instanceof Boolean b ? b : c.vipBonus();
        if (base < 0 || base > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Free slots must be 0 to 100.");
        if (max < 1 || max > 200 || max < base) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The most slots must be at least the free slots (and up to 200).");
        if (price < 0 || price > 1_000_000_000L) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The slot price must be 0 or more.");
        jdbc.update("update tag_settings set base_slots = ?, max_slots = ?, slot_price = ?, vip_bonus = ? where id = 1", base, max, price, vip);
        return rulesMap();
    }

    private int bought(Long userId) {
        Integer b = jdbc.query("select bought from user_tag_slots where user_id = ?", rs -> rs.next() ? rs.getInt(1) : 0, userId);
        return b == null ? 0 : b;
    }

    /** How many tags this person can hold: the free slots, plus slots opened with coins, plus 1 per VIP level while VIP (if the admin allows), up to the most. */
    public int slotsFor(Long userId) {
        SlotRules r = rules();
        int vipExtra = 0;
        if (r.vipBonus()) {
            Integer vip = jdbc.queryForObject(
                    "select case when vip_active and (vip_expires_at is null or vip_expires_at > now()) then coalesce(vip_level, 0) else 0 end from users where id = ?",
                    Integer.class, userId);
            vipExtra = Math.max(0, vip == null ? 0 : vip);
        }
        return Math.min(r.max(), r.base() + vipExtra + bought(userId));
    }

    public Map<String, Object> slots(Long userId) {
        SlotRules r = rules();
        int slots = slotsFor(userId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("used", tags.countFor(userId));
        m.put("slots", slots);
        m.put("max", r.max());
        m.put("price", r.price());
        m.put("canBuy", slots < r.max());
        return m;
    }

    /** Opens one more slot for coins (the price is the admin's). A retry of the same purchase is not charged twice. */
    @Transactional
    public Map<String, Object> buySlot(Long userId) {
        SlotRules r = rules();
        jdbc.queryForObject("select pg_advisory_xact_lock(?)", Object.class, 8_000_000_000L + userId);
        if (slotsFor(userId) >= r.max()) throw new ResponseStatusException(HttpStatus.CONFLICT, "You already have the most slots (" + r.max() + ").");
        int next = bought(userId) + 1;
        if (r.price() > 0) {
            try {
                wallet.debit(userId, r.price(), "TAG_SLOT", "tags", "tag-slot:" + userId + ":" + next, "Opened BFF tag slot " + next);
            } catch (IllegalStateException e) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Not enough coins. A new slot costs " + r.price() + " coins.");
            }
        }
        jdbc.update("insert into user_tag_slots (user_id, bought) values (?, 1) on conflict (user_id) do update set bought = user_tag_slots.bought + 1", userId);
        if (r.price() > 0) walletPush.pushBalance(userId, "TAG_SLOT", -r.price());
        return slots(userId);
    }

    /** Either person can change the relation, the pet name and the theme of their tag. */
    @Transactional
    public Map<String, Object> customize(Long tagId, Long userId, String relation, String nickname, String theme) {
        UserTag t = tags.findById(tagId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "That tag doesn't exist"));
        if (!t.involves(userId)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "That isn't your tag");
        if (relation != null) {
            if (!RELATIONS.contains(relation)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pick one of the tag types");
            // The same type can be used on as many tags as the person has slots for (each tag is with someone different)
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
            case "bro" -> "Brothers";
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
