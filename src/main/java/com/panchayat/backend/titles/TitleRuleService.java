package com.panchayat.backend.titles;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Automatic title rules. Every few minutes (and when the admin saves a rule) each active rule works out who qualifies and gives them the title:
 * a list of user IDs, everyone at a VIP level or above, or everyone with a role in a Parivar. A rule with days = 0 also takes the title back
 * from people who no longer qualify; a rule with days > 0 gives it once, for that many days.
 */
@Service
public class TitleRuleService {

    private static final Logger log = LoggerFactory.getLogger(TitleRuleService.class);
    private static final List<String> ROLES = List.of("LEADER", "DEPUTY", "ADMIN", "MEMBER");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public TitleRuleService(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }

    /** Checks a rule from the admin and returns its cleaned value. */
    public String cleanValue(String kind, String value) {
        String v = value == null ? "" : value.strip();
        switch (kind) {
            case "IDS" -> {
                List<String> ids = parseIds(v).stream().map(String::valueOf).toList();
                if (ids.isEmpty()) throw bad("Write at least one user ID (the 7 digit ID people see on their profile).");
                if (ids.size() > 2000) throw bad("A rule can hold up to 2000 IDs.");
                return String.join(",", ids);
            }
            case "VIP_LEVEL" -> {
                try {
                    int n = Integer.parseInt(v);
                    if (n < 1 || n > 20) throw new NumberFormatException();
                    return String.valueOf(n);
                } catch (NumberFormatException e) {
                    throw bad("Write the lowest VIP level (1 or more).");
                }
            }
            case "PARIVAR_ROLE" -> {
                String r = v.toUpperCase(Locale.ROOT);
                if (!ROLES.contains(r)) throw bad("Choose LEADER, DEPUTY, ADMIN or MEMBER.");
                return r;
            }
            case "TOPUP_TOTAL", "TOPUP_ONE", "GIFT_SENT", "GIFT_RECEIVED" -> {
                try {
                    long n = Long.parseLong(v.replace(",", "").strip());
                    if (n < 1 || n > 1_000_000_000_000L) throw new NumberFormatException();
                    return String.valueOf(n);
                } catch (NumberFormatException e) {
                    throw bad(kind.startsWith("TOPUP") ? "Write the amount in rupees (1 or more)." : "Write the amount in coins (1 or more).");
                }
            }
            default -> throw bad("Choose a rule type.");
        }
    }

    static List<Long> parseIds(String text) {
        LinkedHashSet<Long> out = new LinkedHashSet<>();
        for (String p : text.split("[^0-9]+")) {
            if (p.isEmpty() || p.length() > 12) continue;
            out.add(Long.parseLong(p));
        }
        return new ArrayList<>(out);
    }

    /** Who a rule says should have its title right now. */
    private static final String TOPUPS = "select user_id, amount from (select user_id, amount::numeric as amount from payment_transactions where purpose = 'COIN_PACK' and status = 'SUCCESS'"
            + " union all select user_id, amount_inr::numeric from manual_topups where status = 'APPROVED') t";

    private Set<Long> qualified(String kind, String value) {
        return qualified(kind, value, null);
    }

    /** onlyUser != null checks just that person (used the moment they top up or send a gift). */
    private Set<Long> qualified(String kind, String value, Long onlyUser) {
        Set<Long> out = new LinkedHashSet<>();
        String who = onlyUser == null ? "" : " where user_id = " + onlyUser.longValue();
        switch (kind) {
            case "TOPUP_TOTAL" -> out.addAll(jdbc.queryForList("select user_id from (" + TOPUPS + ")" + " x" + who + " group by user_id having sum(amount) >= ?", Long.class, Long.parseLong(value)));
            case "TOPUP_ONE" -> out.addAll(jdbc.queryForList("select user_id from (" + TOPUPS + ")" + " x" + who + " group by user_id having max(amount) >= ?", Long.class, Long.parseLong(value)));
            case "GIFT_SENT" -> out.addAll(jdbc.queryForList("select sender_id from room_gift_events where sender_id is not null" + (onlyUser == null ? "" : " and sender_id = " + onlyUser.longValue()) + " group by sender_id having sum(coins) >= ?", Long.class, Long.parseLong(value)));
            case "GIFT_RECEIVED" -> out.addAll(jdbc.queryForList("select receiver_id from room_gift_events where receiver_id is not null" + (onlyUser == null ? "" : " and receiver_id = " + onlyUser.longValue()) + " group by receiver_id having sum(coins) >= ?", Long.class, Long.parseLong(value)));
            case "IDS" -> {
                List<Long> pub = parseIds(value);
                if (pub.isEmpty()) return out;
                out.addAll(jdbc.queryForList("select id from users where public_id in (" + pub.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(",")) + ")", Long.class));
            }
            case "VIP_LEVEL" -> out.addAll(jdbc.queryForList("select id from users where vip_active and vip_expires_at > now() and coalesce(vip_level, 0) >= ?", Long.class, Integer.parseInt(value)));
            case "PARIVAR_ROLE" -> out.addAll(jdbc.queryForList("select distinct m.user_id from parivar_members m join parivars p on p.id = m.parivar_id where p.status = 'ACTIVE' and m.role = ?", Long.class, value));
            default -> {
            }
        }
        return out;
    }

    /** Runs every active rule (or one). Returns how many people each rule matched. */
    public Map<Long, Integer> sync(Long onlyRule) {
        Map<Long, Integer> out = new LinkedHashMap<>();
        List<Map<String, Object>> rules = onlyRule == null
                ? jdbc.queryForList("select r.*, t.duration_days as title_days from title_auto_rules r join profile_titles t on t.id = r.title_id where r.active and t.active")
                : jdbc.queryForList("select r.*, t.duration_days as title_days from title_auto_rules r join profile_titles t on t.id = r.title_id where r.id = ?", onlyRule);
        for (Map<String, Object> r : rules) {
            long id = ((Number) r.get("id")).longValue();
            try {
                int n = tx.execute(st -> runOne(r));
                out.put(id, n);
            } catch (Exception e) {
                log.warn("Title rule {} failed: {}", id, e.getMessage());
            }
        }
        return out;
    }

    /** How long the rule's title lasts: the rule's own days, else (for top-up and gift rules) the validity set on the title itself; 0 = no end. */
    private static int effectiveDays(Map<String, Object> r) {
        int days = ((Number) r.get("days")).intValue();
        if (days > 0) return days;
        String kind = (String) r.get("kind");
        boolean money = kind.startsWith("TOPUP") || kind.startsWith("GIFT");
        Object td = r.get("title_days");
        return money && td instanceof Number n ? n.intValue() : 0;
    }

    private int runOne(Map<String, Object> r) {
        long id = ((Number) r.get("id")).longValue();
        long titleId = ((Number) r.get("title_id")).longValue();
        int days = effectiveDays(r);
        String source = "AUTO:" + id;
        Set<Long> q = qualified((String) r.get("kind"), (String) r.get("value"));
        if (days == 0) {
            for (Long u : q) {
                // A person who already owns it another way (bought, given) keeps that; a rule only adds or keeps its own
                jdbc.update("insert into user_titles (user_id, title_id, source) values (?, ?, ?) on conflict (user_id, title_id) do update set expires_at = case when user_titles.source = ? then null else user_titles.expires_at end", u, titleId, source, source);
            }
            // Whoever this rule gave it to and no longer qualifies loses it
            List<Long> have = jdbc.queryForList("select user_id from user_titles where title_id = ? and source = ?", Long.class, titleId, source);
            for (Long u : have) if (!q.contains(u)) jdbc.update("delete from user_titles where user_id = ? and title_id = ? and source = ?", u, titleId, source);
        } else {
            Timestamp exp = Timestamp.from(Instant.now().plus(Duration.ofDays(days)));
            for (Long u : q) {
                if (jdbc.update("insert into title_rule_grants (rule_id, user_id) values (?, ?) on conflict do nothing", id, u) == 0) continue;
                jdbc.update("insert into user_titles (user_id, title_id, source, expires_at) values (?, ?, ?, ?) on conflict (user_id, title_id) do update set expires_at ="
                        + " case when user_titles.expires_at is null then null else greatest(user_titles.expires_at, excluded.expires_at) end", u, titleId, source, exp);
            }
        }
        jdbc.update("update title_auto_rules set matched = ?, last_run = now() where id = ?", q.size(), id);
        return q.size();
    }

    private volatile List<Map<String, Object>> moneyRules;
    private volatile long moneyRulesAt;

    /** The active top-up and gift rules (kept for a minute, so a gift being sent costs nothing extra when there are none). */
    private List<Map<String, Object>> moneyRules() {
        long now = System.currentTimeMillis();
        List<Map<String, Object>> l = moneyRules;
        if (l == null || now - moneyRulesAt > 60_000) {
            l = jdbc.queryForList("select r.*, t.duration_days as title_days from title_auto_rules r join profile_titles t on t.id = r.title_id where r.active and t.active and r.kind in ('TOPUP_TOTAL','TOPUP_ONE','GIFT_SENT','GIFT_RECEIVED')");
            moneyRules = l;
            moneyRulesAt = now;
        }
        return l;
    }

    /**
     * Right after someone tops up or sends / receives a gift: gives them the titles whose top-up or gift amount they have now reached.
     * kinds: "TOPUP" or "GIFT". Never throws; a failure only means the title comes with the next scheduled check.
     */
    public void onMoney(Long userId, String kinds) {
        if (userId == null) return;
        try {
            for (Map<String, Object> r : moneyRules()) {
                String kind = (String) r.get("kind");
                if (!kind.startsWith(kinds)) continue;
                if (!qualified(kind, (String) r.get("value"), userId).contains(userId)) continue;
                long id = ((Number) r.get("id")).longValue();
                long titleId = ((Number) r.get("title_id")).longValue();
                int days = effectiveDays(r);
                String source = "AUTO:" + id;
                if (days == 0) {
                    jdbc.update("insert into user_titles (user_id, title_id, source) values (?, ?, ?) on conflict (user_id, title_id) do update set expires_at = case when user_titles.source = ? then null else user_titles.expires_at end", userId, titleId, source, source);
                } else {
                    // Every top-up (or gift) that keeps them at the amount renews the validity to `days` from now (it never stacks beyond that)
                    jdbc.update("insert into title_rule_grants (rule_id, user_id) values (?, ?) on conflict do nothing", id, userId);
                    jdbc.update("insert into user_titles (user_id, title_id, source, expires_at) values (?, ?, ?, now() + (? * interval '1 day')) on conflict (user_id, title_id) do update set expires_at ="
                            + " case when user_titles.expires_at is null then null else greatest(user_titles.expires_at, now() + (? * interval '1 day')) end", userId, titleId, source, days, days);
                }
            }
        } catch (Exception e) {
            log.debug("Title rule check for {} failed: {}", userId, e.getMessage());
        }
    }

    @Scheduled(initialDelay = 90_000, fixedDelay = 300_000)
    void tick() {
        try {
            sync(null);
        } catch (Exception e) {
            log.warn("Title rules sync failed: {}", e.getMessage());
        }
    }

    /** Everyone with an expired title: tidy the rows so they stop showing up in lists (cheap, once a while). */
    @Scheduled(initialDelay = 120_000, fixedDelay = 3_600_000)
    void tidy() {
        try {
            jdbc.update("delete from user_titles where expires_at is not null and expires_at < now() - interval '1 day'");
        } catch (Exception e) {
            log.debug("Title tidy failed: {}", e.getMessage());
        }
    }
}
