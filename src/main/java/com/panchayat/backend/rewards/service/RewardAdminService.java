package com.panchayat.backend.rewards.service;

import com.panchayat.backend.admin.media.AdminMediaService;
import com.panchayat.backend.admin.media.AdminMediaService.Uploaded;
import com.panchayat.backend.gifts.repository.GiftDefinitionRepository;
import com.panchayat.backend.rewards.entity.RewardRule;
import com.panchayat.backend.rewards.entity.RewardRuleItem;
import com.panchayat.backend.rewards.repository.RewardRuleItemRepository;
import com.panchayat.backend.rewards.repository.RewardRuleRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.util.*;
import java.util.regex.Pattern;

/** Spend reward rules and their items, for the admin panel. */
@Service
public class RewardAdminService {

    /** Built-in item types; admins can add their own ("add more"). */
    public static final List<String> BUILT_IN_TYPES = List.of("GIFT", "CHAT_BUBBLE", "ENTRY_EFFECT", "PROFILE_EFFECT");
    private static final Pattern TYPE = Pattern.compile("^[A-Z][A-Z0-9_]{1,29}$");
    private static final long MAX_THRESHOLD = 1_000_000_000L;

    private final RewardRuleRepository rules;
    private final RewardRuleItemRepository items;
    private final GiftDefinitionRepository gifts;
    private final AdminMediaService media;
    private final JdbcTemplate jdbc;

    public RewardAdminService(RewardRuleRepository rules, RewardRuleItemRepository items, GiftDefinitionRepository gifts,
                              AdminMediaService media, JdbcTemplate jdbc) {
        this.rules = rules;
        this.items = items;
        this.gifts = gifts;
        this.media = media;
        this.jdbc = jdbc;
    }

    public record RuleInput(String name, String kind, Long coinThreshold, Boolean repeatable, Boolean active) {
    }

    public record ItemInput(String name, String itemType, String giftId, Integer quantity, Integer priority,
                            Boolean enabled, Integer durationDays, Boolean forever) {
    }

    /* ---------------- reading ---------------- */

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        Map<Long, long[]> wins = new HashMap<>();
        jdbc.query("select rule_id, count(*), count(distinct user_id) from user_rewards where rule_id is not null group by rule_id",
                rs -> {
                    wins.put(rs.getLong(1), new long[]{rs.getLong(2), rs.getLong(3)});
                });
        return rules.findAllByOrderByKindAscCoinThresholdAsc().stream()
                .map(r -> view(r, wins.getOrDefault(r.getId(), new long[]{0, 0})))
                .toList();
    }

    public List<String> types() {
        LinkedHashSet<String> all = new LinkedHashSet<>(BUILT_IN_TYPES);
        all.addAll(items.usedTypes());
        return List.copyOf(all);
    }

    /** Latest wins, newest first. */
    public Map<String, Object> grants(int page, int size, Long userId) {
        int p = Math.max(0, page);
        int s = Math.min(100, Math.max(1, size));
        String where = userId != null ? " where user_id = ?" : "";
        Object[] args = userId != null ? new Object[]{userId} : new Object[]{};
        Long total = jdbc.queryForObject("select count(*) from user_rewards" + where, Long.class, args);
        List<Object> pageArgs = new ArrayList<>(Arrays.asList(args));
        pageArgs.add(s);
        pageArgs.add((long) p * s);
        List<Map<String, Object>> rows = jdbc.query(
                "select id, user_id, rule_name, rule_kind, item_name, item_type, gift_id, quantity, media_url, media_format, granted_at, expires_at"
                        + " from user_rewards" + where + " order by granted_at desc, id desc limit ? offset ?",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("userId", rs.getLong("user_id"));
                    m.put("ruleName", rs.getString("rule_name"));
                    m.put("ruleKind", rs.getString("rule_kind"));
                    m.put("itemName", rs.getString("item_name"));
                    m.put("itemType", rs.getString("item_type"));
                    m.put("giftId", rs.getString("gift_id"));
                    m.put("quantity", rs.getInt("quantity"));
                    m.put("mediaUrl", rs.getString("media_url"));
                    m.put("mediaFormat", rs.getString("media_format"));
                    m.put("grantedAt", instant(rs.getTimestamp("granted_at")));
                    m.put("expiresAt", instant(rs.getTimestamp("expires_at")));
                    return m;
                }, pageArgs.toArray());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", rows);
        out.put("page", p);
        out.put("size", s);
        out.put("totalElements", total == null ? 0 : total);
        return out;
    }

    /* ---------------- rules ---------------- */

    @Transactional
    public Map<String, Object> createRule(RuleInput in, String admin) {
        if (in == null) throw bad("Fill in the reward's details.");
        RewardRule r = new RewardRule();
        r.setName(text(in.name(), "Give the reward a name.", 80));
        r.setKind(kind(in.kind()));
        r.setCoinThreshold(threshold(in.coinThreshold()));
        r.setRepeatable(in.repeatable() == null || in.repeatable());
        r.setActive(in.active() == null || in.active());
        r.setCreatedBy(admin);
        return view(rules.save(r), new long[]{0, 0});
    }

    @Transactional
    public Map<String, Object> updateRule(Long id, RuleInput in) {
        RewardRule r = rule(id);
        if (in != null) {
            if (in.name() != null) r.setName(text(in.name(), "Give the reward a name.", 80));
            if (in.kind() != null) r.setKind(kind(in.kind()));
            if (in.coinThreshold() != null) r.setCoinThreshold(threshold(in.coinThreshold()));
            if (in.repeatable() != null) r.setRepeatable(in.repeatable());
            if (in.active() != null) {
                if (in.active() && r.getItems().stream().noneMatch(RewardRuleItem::isEnabled)) {
                    throw bad("Add at least one item that can drop before turning this reward on.");
                }
                r.setActive(in.active());
            }
        }
        return view(rules.save(r), new long[]{0, 0});
    }

    @Transactional
    public void deleteRule(Long id) {
        RewardRule r = rule(id);
        List<RewardRuleItem> files = List.copyOf(r.getItems());
        rules.delete(r);
        rules.flush();
        files.forEach(this::deleteFiles);
    }

    /* ---------------- items ---------------- */

    @Transactional
    public Map<String, Object> addItem(Long ruleId, ItemInput in) {
        RewardRule r = rule(ruleId);
        if (in == null) throw bad("Fill in the item's details.");
        if (r.getItems().size() >= 50) throw bad("A reward can have up to 50 items.");
        RewardRuleItem i = new RewardRuleItem();
        i.setRule(r);
        apply(i, in, true);
        r.getItems().add(i);
        rules.saveAndFlush(r);
        return view(r, new long[]{0, 0});
    }

    @Transactional
    public Map<String, Object> updateItem(Long itemId, ItemInput in) {
        RewardRuleItem i = item(itemId);
        if (in != null) apply(i, in, false);
        items.save(i);
        RewardRule r = i.getRule();
        if (r.isActive() && r.getItems().stream().noneMatch(RewardRuleItem::isEnabled)) {
            throw bad("This is the last item that can drop. Turn the reward off first.");
        }
        return view(r, new long[]{0, 0});
    }

    @Transactional
    public Map<String, Object> deleteItem(Long itemId) {
        RewardRuleItem i = item(itemId);
        RewardRule r = i.getRule();
        r.getItems().remove(i);
        if (r.isActive() && r.getItems().stream().noneMatch(RewardRuleItem::isEnabled)) r.setActive(false);
        rules.saveAndFlush(r);
        deleteFiles(i);
        return view(r, new long[]{0, 0});
    }

    /** kind: media (PNG/JPG/JPEG/SVGA/MP4/MP3) or sound (MP3). */
    @Transactional
    public Map<String, Object> upload(Long itemId, String kind, MultipartFile file) {
        RewardRuleItem i = item(itemId);
        if ("sound".equals(kind)) {
            Uploaded u = media.upload(file, "rewards/sounds", AdminMediaService.SOUNDS);
            media.delete(i.getSoundPublicId(), "MP3");
            i.setSoundUrl(u.url());
            i.setSoundPublicId(u.publicId());
        } else {
            Uploaded u = media.upload(file, "rewards/media", AdminMediaService.ANY);
            media.delete(i.getMediaPublicId(), i.getMediaFormat());
            i.setMediaUrl(u.url());
            i.setMediaPublicId(u.publicId());
            i.setMediaFormat(u.format());
        }
        items.save(i);
        return view(i.getRule(), new long[]{0, 0});
    }

    @Transactional
    public Map<String, Object> removeFile(Long itemId, String kind) {
        RewardRuleItem i = item(itemId);
        if ("sound".equals(kind)) {
            media.delete(i.getSoundPublicId(), "MP3");
            i.setSoundUrl(null);
            i.setSoundPublicId(null);
        } else {
            media.delete(i.getMediaPublicId(), i.getMediaFormat());
            i.setMediaUrl(null);
            i.setMediaPublicId(null);
            i.setMediaFormat(null);
        }
        items.save(i);
        return view(i.getRule(), new long[]{0, 0});
    }

    /* ---------------- internals ---------------- */

    private void apply(RewardRuleItem i, ItemInput in, boolean creating) {
        if (in.itemType() != null || creating) i.setItemType(type(in.itemType()));
        if (in.name() != null || creating) i.setName(text(in.name(), "Give the item a name.", 80));
        if ("GIFT".equals(i.getItemType())) {
            String giftId = in.giftId() != null ? in.giftId().trim() : i.getGiftId();
            if (giftId == null || giftId.isEmpty() || !gifts.existsById(giftId)) throw bad("Choose which gift this item gives.");
            i.setGiftId(giftId);
        } else {
            i.setGiftId(null);
        }
        if (in.quantity() != null) {
            if (in.quantity() < 1 || in.quantity() > 9999) throw bad("Quantity must be between 1 and 9,999.");
            i.setQuantity(in.quantity());
        }
        if (in.priority() != null) {
            if (in.priority() < 1 || in.priority() > 4) throw bad("Priority must be 1, 2, 3 or 4.");
            i.setPriority(in.priority());
        }
        if (in.enabled() != null) i.setEnabled(in.enabled());
        if (Boolean.TRUE.equals(in.forever())) {
            i.setDurationDays(null);
        } else if (in.durationDays() != null) {
            if (in.durationDays() < 1 || in.durationDays() > 3650) throw bad("Validity must be between 1 and 3,650 days.");
            i.setDurationDays(in.durationDays());
        }
    }

    private void deleteFiles(RewardRuleItem i) {
        media.delete(i.getMediaPublicId(), i.getMediaFormat());
        media.delete(i.getSoundPublicId(), "MP3");
    }

    /** Chance of each enabled item in a RANDOM rule: weight 5 - priority (P1 = 4 … P4 = 1). */
    static int weight(RewardRuleItem i) {
        return 5 - Math.max(1, Math.min(4, i.getPriority()));
    }

    private Map<String, Object> view(RewardRule r, long[] wins) {
        int total = r.getItems().stream().filter(RewardRuleItem::isEnabled).mapToInt(RewardAdminService::weight).sum();
        List<Map<String, Object>> list = new ArrayList<>();
        for (RewardRuleItem i : r.getItems()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", i.getId());
            m.put("name", i.getName());
            m.put("itemType", i.getItemType());
            m.put("giftId", i.getGiftId());
            m.put("quantity", i.getQuantity());
            m.put("priority", i.getPriority());
            m.put("enabled", i.isEnabled());
            m.put("durationDays", i.getDurationDays());
            m.put("mediaUrl", i.getMediaUrl());
            m.put("mediaFormat", i.getMediaFormat());
            m.put("soundUrl", i.getSoundUrl());
            double chance = r.getKind() == RewardRule.Kind.RANDOM
                    ? (i.isEnabled() && total > 0 ? Math.round(weight(i) * 1000.0 / total) / 10.0 : 0)
                    : (i.isEnabled() ? 100 : 0);
            m.put("chancePercent", chance);
            list.add(m);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("name", r.getName());
        m.put("kind", r.getKind().name());
        m.put("coinThreshold", r.getCoinThreshold());
        m.put("repeatable", r.isRepeatable());
        m.put("active", r.isActive());
        m.put("createdBy", r.getCreatedBy());
        m.put("createdAt", r.getCreatedAt());
        m.put("updatedAt", r.getUpdatedAt());
        m.put("timesWon", wins[0]);
        m.put("winners", wins[1]);
        m.put("items", list);
        return m;
    }

    private RewardRule rule(Long id) {
        return rules.findById(id == null ? -1L : id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "That reward doesn't exist any more."));
    }

    private RewardRuleItem item(Long id) {
        return items.findById(id == null ? -1L : id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "That item doesn't exist any more."));
    }

    private static RewardRule.Kind kind(String k) {
        try {
            return RewardRule.Kind.valueOf(k == null ? "" : k.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw bad("Type must be RANDOM or GUARANTEED.");
        }
    }

    private static long threshold(Long t) {
        if (t == null || t < 1 || t > MAX_THRESHOLD) throw bad("Coins to spend must be between 1 and 1,00,00,00,000.");
        return t;
    }

    private static String type(String raw) {
        String t = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
        if (!TYPE.matcher(t).matches()) {
            throw bad("Item type: 2 to 30 letters, numbers or _ (for example CHAT_BUBBLE or FRAME).");
        }
        return t;
    }

    private static String text(String raw, String missing, int max) {
        String v = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
        if (v.isEmpty()) throw bad(missing);
        if (v.length() > max) throw bad("Names can be up to " + max + " characters.");
        return v;
    }

    private static Object instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }
}
