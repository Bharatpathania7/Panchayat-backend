package com.panchayat.backend.rewards.service;

import com.panchayat.backend.rewards.entity.RewardRule;
import com.panchayat.backend.rewards.entity.RewardRuleItem;
import com.panchayat.backend.rewards.repository.RewardRuleRepository;
import com.panchayat.backend.auth.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Spend rewards for users. Every coin a user spends (gifts today) counts
 * towards each active rule. Crossing a rule's threshold gives a reward:
 * RANDOM rules one item chosen by priority (P1 most likely), GUARANTEED rules
 * every enabled item. Wins are saved in user_rewards in the same transaction
 * as the spend, and pushed to the user's /user/queue/rewards after it commits.
 */
@Service
public class SpendRewardService {

    private static final Logger log = LoggerFactory.getLogger(SpendRewardService.class);
    /** Most wins one spend can give per rule (a huge spend on a small threshold). */
    private static final int MAX_WINS_PER_SPEND = 20;

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

    /** Your wins (still valid) and how close you are to each active reward. */
    @Transactional(readOnly = true)
    public Map<String, Object> mine(Long userId) {
        List<Map<String, Object>> list = jdbc.query(
                "select id, rule_name, rule_kind, item_name, item_type, gift_id, quantity, media_url, media_format, sound_url, granted_at, expires_at"
                        + " from user_rewards where user_id = ? and (expires_at is null or expires_at > now())"
                        + " order by granted_at desc limit 200",
                (rs, n) -> row(rs.getLong("id"), rs.getString("rule_name"), rs.getString("rule_kind"), rs.getString("item_name"),
                        rs.getString("item_type"), rs.getString("gift_id"), rs.getInt("quantity"), rs.getString("media_url"),
                        rs.getString("media_format"), rs.getString("sound_url"), ts(rs.getTimestamp("granted_at")), ts(rs.getTimestamp("expires_at"))),
                userId);
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

    private RewardRuleItem pick(List<RewardRuleItem> pool) {
        int total = pool.stream().mapToInt(RewardAdminService::weight).sum();
        int roll = random.nextInt(total);
        for (RewardRuleItem i : pool) {
            roll -= RewardAdminService.weight(i);
            if (roll < 0) return i;
        }
        return pool.get(pool.size() - 1);
    }

    private Map<String, Object> grant(Long userId, RewardRule r, RewardRuleItem i) {
        Instant now = Instant.now();
        Instant expires = i.getDurationDays() == null ? null : now.plus(Duration.ofDays(i.getDurationDays()));
        Long id = jdbc.queryForObject(
                "insert into user_rewards (user_id, rule_id, rule_item_id, rule_kind, rule_name, item_name, item_type, gift_id,"
                        + " quantity, media_url, media_format, sound_url, granted_at, expires_at)"
                        + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id",
                Long.class,
                userId, r.getId(), i.getId(), r.getKind().name(), r.getName(), i.getName(), i.getItemType(), i.getGiftId(),
                i.getQuantity(), i.getMediaUrl(), i.getMediaFormat(), i.getSoundUrl(), Timestamp.from(now),
                expires == null ? null : Timestamp.from(expires));
        return row(id, r.getName(), r.getKind().name(), i.getName(), i.getItemType(), i.getGiftId(), i.getQuantity(),
                i.getMediaUrl(), i.getMediaFormat(), i.getSoundUrl(), now, expires);
    }

    private static Map<String, Object> row(Long id, String ruleName, String kind, String name, String type, String giftId, int qty,
                                           String mediaUrl, String mediaFormat, String soundUrl, Instant at, Instant expires) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("ruleName", ruleName);
        m.put("ruleKind", kind);
        m.put("itemName", name);
        m.put("itemType", type);
        m.put("giftId", giftId);
        m.put("quantity", qty);
        m.put("mediaUrl", mediaUrl);
        m.put("mediaFormat", mediaFormat);
        m.put("soundUrl", soundUrl);
        m.put("grantedAt", at);
        m.put("expiresAt", expires);
        return m;
    }

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
