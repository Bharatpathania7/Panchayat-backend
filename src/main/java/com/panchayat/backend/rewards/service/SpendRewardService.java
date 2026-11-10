package com.panchayat.backend.rewards.service;

import com.panchayat.backend.admin.media.MediaUrls;
import com.panchayat.backend.auth.repository.UserRepository;
import com.panchayat.backend.rewards.entity.RewardRule;
import com.panchayat.backend.rewards.entity.RewardRuleItem;
import com.panchayat.backend.rewards.repository.RewardRuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Spend rewards for users, and the Bag they land in.
 *
 * Every coin a user spends (gifts) counts towards each active rule. Crossing a
 * rule's threshold gives a reward: RANDOM rules one item chosen by priority
 * (P1 most likely), GUARANTEED rules every enabled item. Wins are saved in
 * user_rewards in the same transaction as the spend, and pushed to the user's
 * /user/queue/rewards after it commits.
 *
 * In the Bag: effects (frame, chat bubble, entry, profile) can be worn, one per
 * slot, and everyone sees what you wear (rooms and profiles read effectsFor);
 * gifts can be sent from the Bag for free.
 */
@Service
public class SpendRewardService {

    private static final Logger log = LoggerFactory.getLogger(SpendRewardService.class);
    /** Most wins one spend can give per rule (a huge spend on a small threshold). */
    private static final int MAX_WINS_PER_SPEND = 20;
    private static final String COLUMNS = "id, rule_name, rule_kind, item_name, item_type, gift_id, quantity, remaining, slot, equipped,"
            + " media_url, media_format, sound_url, granted_at, expires_at";

    private final RewardRuleRepository rules;
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final SimpMessagingTemplate messaging;
    private final SecureRandom random = new SecureRandom();

    public SpendRewardService(RewardRuleRepository rules, JdbcTemplate jdbc, UserRepository users, SimpMessagingTemplate messaging) {
        this.rules = rules;
        this.jdbc = jdbc;
        this.users = users;
        this.messaging = messaging;
    }

    /** Where a worn item shows, from its type; null when it can't be worn (gifts, unknown types). */
    public static String slotOf(String itemType) {
        if (itemType == null || "GIFT".equals(itemType)) return null;
        if (itemType.contains("BUBBLE")) return "BUBBLE";
        if (itemType.contains("ENTRY")) return "ENTRY";
        if (itemType.contains("PROFILE")) return "PROFILE";
        if (itemType.contains("FRAME")) return "FRAME";
        return null;
    }

    /* ---------------- winning ---------------- */

    /** Call in the same transaction as the coin debit. */
    @Transactional
    public List<Map<String, Object>> onSpend(Long userId, long coins) {
        if (userId == null || coins <= 0) return List.of();
        List<Map<String, Object>> won = new ArrayList<>();
        for (RewardRule r : rules.findByActiveTrue()) {
            List<RewardRuleItem> pool = r.getItems().stream().filter(RewardRuleItem::isEnabled).toList();
            if (pool.isEmpty()) continue;

            // Add this spend; the row stays locked until the transaction ends
            Map<String, Object> row = jdbc.queryForMap(
                    "insert into user_spend_progress (user_id, rule_id, spent) values (?, ?, ?)"
                            + " on conflict (user_id, rule_id) do update"
                            + " set spent = user_spend_progress.spent + excluded.spent, updated_at = now()"
                            + " returning spent, triggered",
                    userId, r.getId(), coins);
            long spent = ((Number) row.get("spent")).longValue();
            int triggered = ((Number) row.get("triggered")).intValue();
            if (!r.isRepeatable() && triggered > 0) continue;

            long threshold = r.getCoinThreshold();
            int times = (int) Math.min(spent / threshold, r.isRepeatable() ? MAX_WINS_PER_SPEND : 1);
            if (times <= 0) continue;
            // Repeating rules carry the leftover coins to the next round (beyond the cap, it resets)
            long left = spent - times * threshold;
            if (left >= threshold) left = left % threshold;
            jdbc.update("update user_spend_progress set spent = ?, triggered = triggered + ?, updated_at = now() where user_id = ? and rule_id = ?",
                    left, times, userId, r.getId());

            for (int t = 0; t < times; t++) {
                if (r.getKind() == RewardRule.Kind.RANDOM) {
                    won.add(grant(userId, r, pick(pool)));
                } else {
                    for (RewardRuleItem i : pool) won.add(grant(userId, r, i));
                }
            }
        }
        if (!won.isEmpty()) afterCommit(() -> push(userId, won));
        return won;
    }

    /* ---------------- the Bag ---------------- */

    /** Your Bag (effects still valid, gifts not yet sent) and how close you are to each active reward. */
    @Transactional(readOnly = true)
    public Map<String, Object> mine(Long userId) {
        List<Map<String, Object>> list = jdbc.query(
                "select " + COLUMNS + " from user_rewards where user_id = ?"
                        + " and (expires_at is null or expires_at > now())"
                        + " and (item_type <> 'GIFT' or remaining > 0)"
                        + " order by granted_at desc limit 300",
                ROW, userId);
        Map<Long, long[]> progress = new HashMap<>();
        jdbc.query("select rule_id, spent, triggered from user_spend_progress where user_id = ?",
                rs -> {
                    progress.put(rs.getLong(1), new long[]{rs.getLong(2), rs.getLong(3)});
                }, userId);
        List<Map<String, Object>> goals = new ArrayList<>();
        for (RewardRule r : rules.findByActiveTrue()) {
            if (r.getItems().stream().noneMatch(RewardRuleItem::isEnabled)) continue;
            long[] p = progress.getOrDefault(r.getId(), new long[]{0, 0});
            if (!r.isRepeatable() && p[1] > 0) continue;
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("ruleId", r.getId());
            g.put("name", r.getName());
            g.put("kind", r.getKind().name());
            g.put("coinThreshold", r.getCoinThreshold());
            g.put("spent", p[0]);
            g.put("remaining", Math.max(0, r.getCoinThreshold() - p[0]));
            goals.add(g);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rewards", list);
        out.put("goals", goals);
        return out;
    }

    /** Wear an effect from your Bag; it replaces what you wore in that slot. */
    @Transactional
    public Map<String, Object> wear(Long userId, Long rewardId) {
        Map<String, Object> r = own(userId, rewardId);
        String slot = (String) r.get("slot");
        if (slot == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "This item can't be worn.");
        Object expires = r.get("expiresAt");
        if (expires instanceof Instant e && e.isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This item has expired.");
        }
        jdbc.update("update user_rewards set equipped = false where user_id = ? and slot = ? and equipped", userId, slot);
        jdbc.update("update user_rewards set equipped = true where id = ? and user_id = ?", rewardId, userId);
        return own(userId, rewardId);
    }

    @Transactional
    public Map<String, Object> takeOff(Long userId, Long rewardId) {
        own(userId, rewardId);
        jdbc.update("update user_rewards set equipped = false where id = ? and user_id = ?", rewardId, userId);
        return own(userId, rewardId);
    }

    /**
     * Takes `count` of a gift from the user's Bag (soonest to expire first).
     * False when they don't have that many; nothing is taken then.
     * Call inside the sending transaction: the rows stay locked until it ends.
     */
    @Transactional
    public boolean takeBagGift(Long userId, String giftId, int count) {
        List<long[]> rows = jdbc.query(
                "select id, remaining from user_rewards where user_id = ? and gift_id = ? and remaining > 0"
                        + " and (expires_at is null or expires_at > now())"
                        + " order by expires_at asc nulls last, id asc for update",
                (rs, i) -> new long[]{rs.getLong(1), rs.getLong(2)}, userId, giftId);
        long have = rows.stream().mapToLong(r -> r[1]).sum();
        if (have < count) return false;
        int need = count;
        for (long[] r : rows) {
            if (need <= 0) break;
            int take = (int) Math.min(need, r[1]);
            jdbc.update("update user_rewards set remaining = remaining - ? where id = ?", take, r[0]);
            need -= take;
        }
        return true;
    }

    /**
     * What each person wears from their Bag, for rooms and profiles:
     * userId -> { FRAME | BUBBLE | ENTRY | PROFILE -> { url, format, soundUrl, name } }.
     * Expired items are left out.
     */
    @Transactional(readOnly = true)
    public Map<Long, Map<String, Map<String, Object>>> effectsFor(Collection<Long> userIds) {
        Map<Long, Map<String, Map<String, Object>>> out = new HashMap<>();
        List<Long> ids = userIds == null ? List.of() : userIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return out;
        String in = String.join(",", Collections.nCopies(ids.size(), "?"));
        jdbc.query("select user_id, slot, item_name, media_url, media_format, sound_url from user_rewards"
                        + " where equipped and slot is not null and media_url is not null"
                        + " and (expires_at is null or expires_at > now()) and user_id in (" + in + ")",
                rs -> {
                    Map<String, Object> fx = new LinkedHashMap<>();
                    fx.put("url", MediaUrls.link(rs.getString("media_url")));
                    fx.put("format", rs.getString("media_format"));
                    fx.put("soundUrl", MediaUrls.link(rs.getString("sound_url")));
                    fx.put("name", rs.getString("item_name"));
                    out.computeIfAbsent(rs.getLong("user_id"), k -> new LinkedHashMap<>()).put(rs.getString("slot"), fx);
                }, ids.toArray());
        return out;
    }

    public Map<String, Map<String, Object>> effectsFor(Long userId) {
        return userId == null ? Map.of() : effectsFor(List.of(userId)).getOrDefault(userId, Map.of());
    }

    /* ---------------- internals ---------------- */

    private Map<String, Object> own(Long userId, Long rewardId) {
        List<Map<String, Object>> r = jdbc.query("select " + COLUMNS + " from user_rewards where id = ? and user_id = ?", ROW, rewardId, userId);
        if (r.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That item isn't in your Bag.");
        return r.get(0);
    }

    private RewardRuleItem pick(List<RewardRuleItem> pool) {
        int total = pool.stream().mapToInt(RewardAdminService::weight).sum();
        int roll = random.nextInt(total);
        for (RewardRuleItem i : pool) {
            roll -= RewardAdminService.weight(i);
            if (roll < 0) return i;
        }
        return pool.get(pool.size() - 1);
    }

    /** A gift card won in Parivar Gacha: `qty` of a gift into the person's Bag (it expires after `days` days, 0 = never). */
    @Transactional
    public void grantGachaGift(Long userId, String giftId, int qty, int days) {
        String name = jdbc.query("select name from gifts where id = ?", rs -> rs.next() ? rs.getString(1) : giftId, giftId);
        Instant now = Instant.now();
        jdbc.update("insert into user_rewards (user_id, rule_id, rule_item_id, rule_kind, rule_name, item_name, item_type, gift_id, quantity, remaining, slot, granted_at, expires_at)"
                        + " values (?, null, null, 'GACHA', 'Parivar Gacha', ?, 'GIFT', ?, ?, ?, null, ?, ?)",
                userId, name + " Gift Card", giftId, qty, qty, Timestamp.from(now), days > 0 ? Timestamp.from(now.plus(Duration.ofDays(days))) : null);
    }

    /** A frame, chat bubble or entry animation won in Parivar Gacha, from the Spend rewards items, for `days` days (0 = the item's own length). */
    @Transactional
    public String grantGachaItem(Long userId, long ruleItemId, int days) {
        List<Map<String, Object>> it = jdbc.queryForList("select name, item_type, duration_days, media_url, media_format, sound_url from reward_rule_items where id = ?", ruleItemId);
        if (it.isEmpty()) throw new IllegalStateException("The Gacha item " + ruleItemId + " doesn't exist");
        Map<String, Object> i = it.get(0);
        int len = days > 0 ? days : (i.get("duration_days") == null ? 0 : ((Number) i.get("duration_days")).intValue());
        Instant now = Instant.now();
        String type = (String) i.get("item_type");
        jdbc.update("insert into user_rewards (user_id, rule_id, rule_item_id, rule_kind, rule_name, item_name, item_type, gift_id, quantity, remaining, slot, media_url, media_format, sound_url, granted_at, expires_at)"
                        + " values (?, null, ?, 'GACHA', 'Parivar Gacha', ?, ?, null, 1, null, ?, ?, ?, ?, ?, ?)",
                userId, ruleItemId, i.get("name"), type, slotOf(type), i.get("media_url"), i.get("media_format"), i.get("sound_url"), Timestamp.from(now),
                len > 0 ? Timestamp.from(now.plus(Duration.ofDays(len))) : null);
        return (String) i.get("name");
    }

    private Map<String, Object> grant(Long userId, RewardRule r, RewardRuleItem i) {
        Instant now = Instant.now();
        Instant expires = i.getDurationDays() == null ? null : now.plus(Duration.ofDays(i.getDurationDays()));
        boolean gift = "GIFT".equals(i.getItemType());
        Long id = jdbc.queryForObject(
                "insert into user_rewards (user_id, rule_id, rule_item_id, rule_kind, rule_name, item_name, item_type, gift_id,"
                        + " quantity, remaining, slot, media_url, media_format, sound_url, granted_at, expires_at)"
                        + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id",
                Long.class,
                userId, r.getId(), i.getId(), r.getKind().name(), r.getName(), i.getName(), i.getItemType(), i.getGiftId(),
                i.getQuantity(), gift ? i.getQuantity() : null, slotOf(i.getItemType()), i.getMediaUrl(), i.getMediaFormat(), i.getSoundUrl(),
                Timestamp.from(now), expires == null ? null : Timestamp.from(expires));
        return own(userId, id);
    }

    private static final RowMapper<Map<String, Object>> ROW = (rs, n) -> {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getLong("id"));
        m.put("ruleName", rs.getString("rule_name"));
        m.put("ruleKind", rs.getString("rule_kind"));
        m.put("itemName", rs.getString("item_name"));
        m.put("itemType", rs.getString("item_type"));
        m.put("giftId", rs.getString("gift_id"));
        m.put("quantity", rs.getInt("quantity"));
        m.put("remaining", rs.getObject("remaining"));
        m.put("slot", rs.getString("slot"));
        m.put("equipped", rs.getBoolean("equipped"));
        m.put("mediaUrl", MediaUrls.link(rs.getString("media_url")));
        m.put("mediaFormat", rs.getString("media_format"));
        m.put("soundUrl", MediaUrls.link(rs.getString("sound_url")));
        m.put("grantedAt", ts(rs.getTimestamp("granted_at")));
        m.put("expiresAt", ts(rs.getTimestamp("expires_at")));
        return m;
    };

    private void push(Long userId, List<Map<String, Object>> won) {
        try {
            users.findById(userId).ifPresent(u -> {
                if (u.getEmail() == null || u.getEmail().isBlank()) return;
                Object payload = Map.of("rewards", won);
                messaging.convertAndSendToUser(u.getEmail(), "/queue/rewards", payload);
            });
        } catch (Exception e) {
            log.debug("Reward push failed for userId={}: {}", userId, e.getMessage());
        }
    }

    private static Instant ts(Timestamp t) {
        return t == null ? null : t.toInstant();
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
