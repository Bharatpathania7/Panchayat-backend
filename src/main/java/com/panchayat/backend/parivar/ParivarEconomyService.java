package com.panchayat.backend.parivar;

import com.panchayat.backend.admin.media.MediaUrls;
import com.panchayat.backend.wallet.service.CoinWalletService;
import com.panchayat.backend.wallet.service.WalletPushService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/**
 * The Parivar inventory and rewards, shop, cheer, tasks, check-in, news posts, family chat and chat settings.
 * Prices, limits and rewards are rows edited in the admin panel; this class only applies them.
 */
@Service
public class ParivarEconomyService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private final SecureRandom random = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final ParivarService core;
    private final CoinWalletService wallet;
    private final WalletPushService walletPush;

    public ParivarEconomyService(JdbcTemplate jdbc, ParivarService core, CoinWalletService wallet, WalletPushService walletPush) {
        this.jdbc = jdbc;
        this.core = core;
        this.wallet = wallet;
        this.walletPush = walletPush;
    }

    private static long l(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }

    private Timestamp dayStart() {
        return Timestamp.from(LocalDate.now(IST).atStartOfDay(IST).toInstant());
    }

    private Timestamp weekStart() {
        return core.weekStart();
    }

    private int level(Long parivarId) {
        return ((Number) core.activeParivar(parivarId).get("level")).intValue();
    }

    private void member(Long parivarId, Long userId) {
        core.activeParivar(parivarId);
        if (core.roleOf(parivarId, userId) == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only members can do this.");
    }

    private void feature(String column, String name) {
        Boolean on = jdbc.query("select " + column + " from parivar_settings where id = 1", rs -> rs.next() ? rs.getBoolean(1) : false);
        if (!Boolean.TRUE.equals(on)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, name + " is switched off right now.");
    }

    /* ======================= inventory and rewards ======================= */

    /** Gacha tickets belong to a person. */
    void addTickets(Long userId, long qty) {
        jdbc.update("insert into parivar_user_tickets (user_id, qty) values (?, ?) on conflict (user_id) do update set qty = parivar_user_tickets.qty + excluded.qty, updated_at = now()", userId, qty);
    }

    /** Takes tickets from a person; false when they don't have that many. */
    boolean takeTickets(Long userId, long qty) {
        return jdbc.update("update parivar_user_tickets set qty = qty - ?, updated_at = now() where user_id = ? and qty >= ?", qty, userId, qty) > 0;
    }

    long tickets(Long userId) {
        return l(jdbc.query("select qty from parivar_user_tickets where user_id = ?", rs -> rs.next() ? rs.getLong(1) : 0L, userId));
    }

    void addInventory(Long parivarId, String kind, long ref, long qty) {
        jdbc.update("insert into parivar_inventory (parivar_id, kind, ref, qty) values (?, ?, ?, ?) on conflict (parivar_id, kind, ref) do update set qty = parivar_inventory.qty + excluded.qty", parivarId, kind, ref, qty);
    }

    /** Takes one or more items from the inventory; false when there aren't enough. */
    boolean takeInventory(Long parivarId, String kind, long ref, long qty) {
        return jdbc.update("update parivar_inventory set qty = qty - ? where parivar_id = ? and kind = ? and ref = ? and qty >= ?", qty, parivarId, kind, ref, qty) > 0;
    }

    public List<Map<String, Object>> inventory(Long userId, Long parivarId) {
        member(parivarId, userId);
        return jdbc.queryForList("select i.kind, i.ref, i.qty, coalesce(m.name, c.name, b.name, case when i.kind = 'ATTEMPT' then 'Stealing Attempts' end) as name from parivar_inventory i"
                + " left join parivar_gacha_machines m on i.kind = 'GACHA_CARD' and m.id = i.ref left join parivar_chest_types c on i.kind = 'CHEST' and c.id = i.ref"
                + " left join parivar_barrier_types b on i.kind = 'BARRIER' and b.id = i.ref where i.parivar_id = ? and i.qty > 0 order by i.kind, i.ref", parivarId);
    }

    /** Gives a reward: COINS and CHARM go to the person, FUNDS to the treasury, CHEST / CARD / BARRIER to the Parivar inventory. */
    void applyReward(Long parivarId, Long userId, String kind, long ref, long amount, String key, String why) {
        switch (kind) {
            case "COINS" -> {
                wallet.credit(userId, amount, "PARIVAR_REWARD", "parivar:" + parivarId, "pv-reward:" + key, why);
                walletPush.pushBalance(userId, "PARIVAR_REWARD", amount);
            }
            case "CHARM" -> jdbc.update("update users set charm_points = charm_points + ? where id = ?", amount, userId);
            case "FUNDS" -> core.changeFunds(parivarId, userId, amount, "REWARD", "reward:" + key);
            case "CHEST" -> addInventory(parivarId, "CHEST", ref, amount);
            case "CARD" -> addTickets(userId, amount);
            case "WEAPON" -> addInventory(parivarId, "ATTEMPT", 0, amount);
            case "BARRIER" -> addInventory(parivarId, "BARRIER", ref, amount);
            case "EXP" -> core.addExp(parivarId, userId, amount);
            default -> throw new IllegalStateException("Unknown reward kind " + kind);
        }
    }

    /** One row picked by weight (a bigger weight is a bigger chance). */
    Map<String, Object> pickWeighted(List<Map<String, Object>> rows) {
        long total = 0;
        for (Map<String, Object> r : rows) total += l(r.get("weight"));
        if (total <= 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Nothing to win here right now.");
        long roll = (long) (random.nextDouble() * total);
        long acc = 0;
        for (Map<String, Object> r : rows) {
            acc += l(r.get("weight"));
            if (roll < acc) return r;
        }
        return rows.get(rows.size() - 1);
    }

    /* ======================= shop ======================= */

    public List<Map<String, Object>> shop(Long userId, Long parivarId) {
        member(parivarId, userId);
        feature("shop_enabled", "The Parivar shop");
        int lvl = level(parivarId);
        return jdbc.query("select s.*, (select coalesce(sum(qty), 0) from parivar_shop_log g where g.parivar_id = ? and g.item_id = s.id and g.created_at >= ?) as bought_today"
                        + " from parivar_shop_items s where s.active and s.min_level <= ? order by s.sort_order, s.id",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", String.valueOf(rs.getLong("id")));
                    m.put("name", rs.getString("name"));
                    m.put("imageUrl", MediaUrls.link(rs.getString("image_url")));
                    m.put("kind", rs.getString("kind"));
                    m.put("qty", rs.getInt("qty"));
                    m.put("priceFunds", rs.getLong("price_funds"));
                    m.put("priceCoins", rs.getLong("price_coins"));
                    m.put("dailyLimit", rs.getInt("daily_limit"));
                    m.put("boughtToday", rs.getLong("bought_today"));
                    return m;
                }, parivarId, dayStart(), lvl);
    }

    @Transactional
    public Map<String, Object> buy(Long userId, Long parivarId, Long itemId, String payWith, String key) {
        if (key == null || key.isBlank() || key.length() > 60) throw ParivarService.bad("A request key is required.");
        member(parivarId, userId);
        feature("shop_enabled", "The Parivar shop");
        List<Map<String, Object>> it = jdbc.queryForList("select * from parivar_shop_items where id = ? and active", itemId);
        if (it.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That item isn't on sale.");
        Map<String, Object> item = it.get(0);
        if (level(parivarId) < ((Number) item.get("min_level")).intValue()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Your Parivar needs level " + item.get("min_level") + " for this item.");
        String ref = "shop:" + userId + ":" + key.strip();
        if (l(jdbc.queryForObject("select count(*) from parivar_shop_log where ref = ?", Long.class, ref)) > 0) return Map.of("ok", true);
        int qty = ((Number) item.get("qty")).intValue();
        int limit = ((Number) item.get("daily_limit")).intValue();
        if (limit > 0) {
            long today = l(jdbc.queryForObject("select coalesce(sum(qty), 0) from parivar_shop_log where parivar_id = ? and item_id = ? and created_at >= ?", Long.class, parivarId, itemId, dayStart()));
            if (today + qty > limit) throw new ResponseStatusException(HttpStatus.CONFLICT, "Your Parivar has bought the daily limit of this item.");
        }
        long funds = 0, coins = 0;
        if ("COINS".equalsIgnoreCase(payWith)) {
            coins = l(item.get("price_coins"));
            if (coins <= 0) throw ParivarService.bad("This item can't be bought with coins.");
            try {
                wallet.debit(userId, coins, "PARIVAR_SHOP", "parivar:" + parivarId, "pv-" + ref, "Parivar shop: " + item.get("name"));
            } catch (IllegalStateException e) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Not enough coins.");
            }
            walletPush.pushBalance(userId, "PARIVAR_SHOP", -coins);
        } else {
            funds = l(item.get("price_funds"));
            if (funds <= 0) throw ParivarService.bad("This item can't be bought with Parivar funds.");
            core.require(parivarId, userId, "MANAGE_TREASURY");
            core.changeFunds(parivarId, userId, -funds, "SHOP", ref);
        }
        addInventory(parivarId, (String) item.get("kind"), l(item.get("ref")), qty);
        jdbc.update("insert into parivar_shop_log (parivar_id, user_id, item_id, qty, funds, coins, ref) values (?, ?, ?, ?, ?, ?, ?)", parivarId, userId, itemId, qty, funds, coins, ref);
        return Map.of("ok", true);
    }

    /* ======================= cheer ======================= */

    public List<Map<String, Object>> cheerTiers(Long userId, Long parivarId) {
        member(parivarId, userId);
        return jdbc.query("select t.*, (select coalesce(sum(a.qty), 0) from parivar_activity a where a.parivar_id = ? and a.user_id = ? and a.kind = 'CHEER' and a.ref like ? and a.created_at >= ?) as used_today"
                        + " from parivar_cheer_tiers t where t.active order by t.sort_order, t.id",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", String.valueOf(rs.getLong("id")));
                    m.put("name", rs.getString("name"));
                    m.put("times", rs.getInt("times"));
                    m.put("coinsCost", rs.getLong("coins_cost"));
                    m.put("expGain", rs.getLong("exp_gain"));
                    m.put("dailyLimit", rs.getInt("daily_limit"));
                    return m;
                }, parivarId, userId, "%", dayStart());
    }

    @Transactional
    public Map<String, Object> cheer(Long userId, Long parivarId, Long tierId, String key) {
        if (key == null || key.isBlank() || key.length() > 60) throw ParivarService.bad("A request key is required.");
        member(parivarId, userId);
        feature("cheer_enabled", "Cheer");
        List<Map<String, Object>> t = jdbc.queryForList("select * from parivar_cheer_tiers where id = ? and active", tierId);
        if (t.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That cheer isn't available.");
        Map<String, Object> tier = t.get(0);
        String ref = "cheer:" + userId + ":" + key.strip();
        if (l(jdbc.queryForObject("select count(*) from parivar_activity where ref = ?", Long.class, ref)) > 0) return Map.of("ok", true);
        int limit = ((Number) tier.get("daily_limit")).intValue();
        long usedToday = l(jdbc.queryForObject("select count(*) from parivar_activity where parivar_id = ? and user_id = ? and kind = 'CHEER' and ref like ? and created_at >= ?", Long.class, parivarId, userId, "cheer:" + userId + ":%", dayStart()));
        // the limit counts how many times this tier was sent today
        long sameTier = l(jdbc.queryForObject("select count(*) from parivar_activity where parivar_id = ? and user_id = ? and kind = 'CHEER' and label = ? and created_at >= ?", Long.class, parivarId, userId, "cheered x" + tier.get("times"), dayStart()));
        if (limit > 0 && sameTier >= limit) throw new ResponseStatusException(HttpStatus.CONFLICT, "You've used all of today's " + tier.get("name") + ".");
        long cost = l(tier.get("coins_cost"));
        if (cost > 0) {
            try {
                wallet.debit(userId, cost, "PARIVAR_CHEER", "parivar:" + parivarId, "pv-" + ref, "Cheer for Parivar " + parivarId);
            } catch (IllegalStateException e) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Not enough coins.");
            }
            walletPush.pushBalance(userId, "PARIVAR_CHEER", -cost);
        }
        long exp = l(tier.get("exp_gain"));
        long fundsGain = l(tier.get("funds_gain"));
        int times = ((Number) tier.get("times")).intValue();
        jdbc.update("insert into parivar_activity (parivar_id, user_id, kind, exp, coins, label, ref, qty) values (?, ?, 'CHEER', ?, ?, ?, ?, ?)", parivarId, userId, exp, cost, "cheered x" + times, ref, times);
        if (fundsGain > 0) core.changeFunds(parivarId, userId, fundsGain, "CHEER", "cheerfunds:" + ref);
        if (exp > 0) core.addExp(parivarId, userId, exp);
        if (usedToday < 0) throw new IllegalStateException();
        return Map.of("ok", true, "times", times, "exp", exp);
    }

    /* ======================= check-in and tasks ======================= */

    @Transactional
    public Map<String, Object> checkin(Long userId, Long parivarId) {
        member(parivarId, userId);
        long exp = l(core.settings().get("checkin_exp"));
        String ref = "checkin:" + parivarId + ":" + userId + ":" + LocalDate.now(IST);
        int n = jdbc.update("insert into parivar_activity (parivar_id, user_id, kind, exp, label, ref) values (?, ?, 'CHECKIN', ?, 'checked in', ?) on conflict do nothing", parivarId, userId, exp, ref);
        if (n == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "You've already checked in today.");
        if (exp > 0) core.addExp(parivarId, userId, exp);
        return Map.of("ok", true, "exp", exp);
    }

    private String periodKey(String period) {
        return "WEEKLY".equals(period) ? "W" + LocalDate.now(IST).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) : "D" + LocalDate.now(IST);
    }

    private long progress(Long parivarId, Long userId, Map<String, Object> task) {
        Timestamp from = "WEEKLY".equals(task.get("period")) ? weekStart() : dayStart();
        String sql = switch ((String) task.get("metric")) {
            case "GIFT_EXP" -> "select coalesce(sum(exp), 0) from parivar_activity where kind = 'GIFT'";
            case "DONATED_COINS" -> "select coalesce(sum(coins), 0) from parivar_activity where kind = 'DONATION'";
            case "CHEERS" -> "select coalesce(sum(qty), 0) from parivar_activity where kind = 'CHEER'";
            case "CHECKINS" -> "select count(*) from parivar_activity where kind = 'CHECKIN'";
            case "GACHA_ROLLS" -> "select coalesce(sum(qty), 0) from parivar_activity where kind = 'GACHA'";
            default -> "select coalesce(sum(exp), 0) from parivar_activity where true";
        };
        return l(jdbc.queryForObject(sql + " and parivar_id = ? and user_id = ? and created_at >= ?", Long.class, parivarId, userId, from));
    }

    public Map<String, Object> tasks(Long userId, Long parivarId) {
        member(parivarId, userId);
        feature("tasks_enabled", "Tasks");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> t : jdbc.queryForList("select * from parivar_tasks where active order by period, sort_order, id")) {
            long have = progress(parivarId, userId, t);
            long target = l(t.get("target"));
            boolean claimed = l(jdbc.queryForObject("select count(*) from parivar_task_claims where task_id = ? and user_id = ? and period_key = ?", Long.class, t.get("id"), userId, periodKey((String) t.get("period")))) > 0;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", String.valueOf(t.get("id")));
            m.put("title", t.get("title"));
            m.put("period", t.get("period"));
            m.put("metric", t.get("metric"));
            m.put("target", target);
            m.put("progress", Math.min(have, target));
            m.put("rewardKind", t.get("reward_kind"));
            m.put("rewardAmount", l(t.get("reward_amount")));
            m.put("claimed", claimed);
            m.put("claimable", !claimed && have >= target);
            out.add(m);
        }
        long todayExp = l(jdbc.queryForObject("select coalesce(sum(exp), 0) from parivar_activity where parivar_id = ? and user_id = ? and created_at >= ?", Long.class, parivarId, userId, dayStart()));
        long weekExp = l(jdbc.queryForObject("select coalesce(sum(exp), 0) from parivar_activity where parivar_id = ? and user_id = ? and created_at >= ?", Long.class, parivarId, userId, weekStart()));
        boolean checkedIn = l(jdbc.queryForObject("select count(*) from parivar_activity where parivar_id = ? and user_id = ? and kind = 'CHECKIN' and created_at >= ?", Long.class, parivarId, userId, dayStart())) > 0;
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("tasks", out);
        res.put("todayExp", todayExp);
        res.put("weekExp", weekExp);
        res.put("checkedIn", checkedIn);
        res.put("checkinExp", l(core.settings().get("checkin_exp")));
        res.put("dayResetsAt", LocalDate.now(IST).plusDays(1).atStartOfDay(IST).toInstant().toString());
        res.put("weekResetsAt", LocalDate.now(IST).with(TemporalAdjusters.next(DayOfWeek.MONDAY)).atStartOfDay(IST).toInstant().toString());
        return res;
    }

    @Transactional
    public Map<String, Object> claimTask(Long userId, Long parivarId, Long taskId) {
        member(parivarId, userId);
        feature("tasks_enabled", "Tasks");
        List<Map<String, Object>> l = jdbc.queryForList("select * from parivar_tasks where id = ? and active", taskId);
        if (l.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That task isn't available.");
        Map<String, Object> t = l.get(0);
        if (progress(parivarId, userId, t) < l(t.get("target"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "Finish the task first.");
        String pk = periodKey((String) t.get("period"));
        int n = jdbc.update("insert into parivar_task_claims (task_id, parivar_id, user_id, period_key) values (?, ?, ?, ?) on conflict do nothing", taskId, parivarId, userId, pk);
        if (n == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "You've already claimed this.");
        applyReward(parivarId, userId, (String) t.get("reward_kind"), 0, l(t.get("reward_amount")), "task:" + taskId + ":" + userId + ":" + pk, "Parivar task: " + t.get("title"));
        return Map.of("ok", true, "rewardKind", t.get("reward_kind"), "rewardAmount", l(t.get("reward_amount")));
    }

    /* ======================= news ======================= */

    public List<Map<String, Object>> news(Long userId, Long parivarId) {
        member(parivarId, userId);
        return jdbc.query("select n.id, n.kind, n.title, n.body, n.pinned, n.expires_at, n.created_at, u.full_name as author,"
                        + " exists (select 1 from parivar_news_reads r where r.news_id = n.id and r.user_id = ?) as read_by_me from parivar_news n left join users u on u.id = n.author_id"
                        + " where n.parivar_id = ? and (n.expires_at is null or n.expires_at > now()) order by n.pinned desc, n.id desc limit 50",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", String.valueOf(rs.getLong("id")));
                    m.put("kind", rs.getString("kind"));
                    m.put("title", rs.getString("title"));
                    m.put("body", rs.getString("body"));
                    m.put("pinned", rs.getBoolean("pinned"));
                    m.put("author", rs.getString("author"));
                    m.put("at", rs.getTimestamp("created_at").toInstant().toString());
                    m.put("unread", !rs.getBoolean("read_by_me"));
                    return m;
                }, userId, parivarId);
    }

    public long unreadNews(Long userId, Long parivarId) {
        return l(jdbc.queryForObject("select count(*) from parivar_news n where n.parivar_id = ? and (n.expires_at is null or n.expires_at > now())"
                + " and not exists (select 1 from parivar_news_reads r where r.news_id = n.id and r.user_id = ?)", Long.class, parivarId, userId));
    }

    public record NewsRequest(String kind, String title, String body, Boolean pinned, Integer expiresInDays) {
    }

    @Transactional
    public Map<String, Object> postNews(Long userId, Long parivarId, NewsRequest r) {
        core.activeParivar(parivarId);
        core.require(parivarId, userId, "EDIT_ANNOUNCEMENT");
        String title = r == null || r.title() == null ? "" : r.title().strip();
        String body = r == null || r.body() == null ? "" : r.body().strip();
        if (title.length() < 2 || title.length() > 80) throw ParivarService.bad("The title must be 2 to 80 letters.");
        if (body.isEmpty() || body.length() > 1000) throw ParivarService.bad("The text must be 1 to 1000 letters.");
        String kind = r.kind() != null && List.of("NOTICE", "RULE", "EVENT").contains(r.kind().toUpperCase(Locale.ROOT)) ? r.kind().toUpperCase(Locale.ROOT) : "NOTICE";
        boolean pin = Boolean.TRUE.equals(r.pinned());
        if (pin) {
            // how many posts can be pinned: 3
            if (l(jdbc.queryForObject("select count(*) from parivar_news where parivar_id = ? and pinned", Long.class, parivarId)) >= 3) throw new ResponseStatusException(HttpStatus.CONFLICT, "You can pin up to 3 posts. Unpin one first.");
        }
        Timestamp exp = r.expiresInDays() == null || r.expiresInDays() <= 0 ? null : Timestamp.from(java.time.Instant.now().plus(java.time.Duration.ofDays(Math.min(r.expiresInDays(), 365))));
        Long id = jdbc.queryForObject("insert into parivar_news (parivar_id, author_id, kind, title, body, pinned, expires_at) values (?, ?, ?, ?, ?, ?, ?) returning id", Long.class, parivarId, userId, kind, title, body, pin, exp);
        jdbc.update("insert into parivar_news_reads (news_id, user_id) values (?, ?)", id, userId);
        core.system(parivarId, "📢 New " + kind.toLowerCase(Locale.ROOT) + ": " + title);
        return Map.of("id", String.valueOf(id));
    }

    @Transactional
    public Map<String, Object> pinNews(Long userId, Long parivarId, Long newsId, boolean pin) {
        core.require(parivarId, userId, "EDIT_ANNOUNCEMENT");
        if (pin && l(jdbc.queryForObject("select count(*) from parivar_news where parivar_id = ? and pinned and id <> ?", Long.class, parivarId, newsId)) >= 3) throw new ResponseStatusException(HttpStatus.CONFLICT, "You can pin up to 3 posts. Unpin one first.");
        jdbc.update("update parivar_news set pinned = ? where id = ? and parivar_id = ?", pin, newsId, parivarId);
        return Map.of("ok", true);
    }

    @Transactional
    public Map<String, Object> deleteNews(Long userId, Long parivarId, Long newsId) {
        core.require(parivarId, userId, "EDIT_ANNOUNCEMENT");
        jdbc.update("delete from parivar_news where id = ? and parivar_id = ?", newsId, parivarId);
        return Map.of("ok", true);
    }

    @Transactional
    public Map<String, Object> markNewsRead(Long userId, Long parivarId) {
        member(parivarId, userId);
        jdbc.update("insert into parivar_news_reads (news_id, user_id) select n.id, ? from parivar_news n where n.parivar_id = ? on conflict do nothing", userId, parivarId);
        return Map.of("ok", true);
    }

    /* ======================= family chat ======================= */

    public Map<String, Object> chat(Long userId, Long parivarId, long after) {
        member(parivarId, userId);
        feature("chat_enabled", "Family chat");
        List<Map<String, Object>> rows = jdbc.query("select c.id, c.user_id, c.kind, c.text, c.created_at, u.full_name, u.avatar_url from parivar_chat c left join users u on u.id = c.user_id"
                        + " where c.parivar_id = ? and c.id > ? order by c.id desc limit 60",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("userId", rs.getObject("user_id") == null ? null : String.valueOf(rs.getLong("user_id")));
                    m.put("kind", rs.getString("kind"));
                    m.put("text", rs.getString("text"));
                    m.put("name", rs.getString("full_name"));
                    m.put("avatarUrl", MediaUrls.link(rs.getString("avatar_url")));
                    m.put("at", rs.getTimestamp("created_at").toInstant().toString());
                    return m;
                }, parivarId, after);
        Collections.reverse(rows);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("messages", rows);
        res.put("prefs", prefs(userId, parivarId));
        res.put("me", String.valueOf(userId));
        return res;
    }

    @Transactional
    public Map<String, Object> sendChat(Long userId, Long parivarId, String text) {
        member(parivarId, userId);
        feature("chat_enabled", "Family chat");
        String t = text == null ? "" : text.strip();
        if (t.isEmpty() || t.length() > 500) throw ParivarService.bad("Write a message of 1 to 500 letters.");
        // a little spam guard: 5 messages in 10 seconds
        if (l(jdbc.queryForObject("select count(*) from parivar_chat where parivar_id = ? and user_id = ? and created_at > now() - interval '10 seconds'", Long.class, parivarId, userId)) >= 5)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Slow down a little.");
        jdbc.update("insert into parivar_chat (parivar_id, user_id, kind, text) values (?, ?, 'TEXT', ?)", parivarId, userId, t);
        return Map.of("ok", true);
    }

    public Map<String, Object> prefs(Long userId, Long parivarId) {
        List<Map<String, Object>> l = jdbc.queryForList("select muted, fold_gifts as \"foldGifts\", gift_sound as \"giftSound\", background from parivar_member_prefs where parivar_id = ? and user_id = ?", parivarId, userId);
        return l.isEmpty() ? new LinkedHashMap<>(Map.of("muted", false, "foldGifts", false, "giftSound", true, "background", "default")) : l.get(0);
    }

    public record PrefsRequest(Boolean muted, Boolean foldGifts, Boolean giftSound, String background) {
    }

    @Transactional
    public Map<String, Object> setPrefs(Long userId, Long parivarId, PrefsRequest r) {
        member(parivarId, userId);
        Map<String, Object> cur = prefs(userId, parivarId);
        boolean muted = r.muted() != null ? r.muted() : Boolean.TRUE.equals(cur.get("muted"));
        boolean fold = r.foldGifts() != null ? r.foldGifts() : Boolean.TRUE.equals(cur.get("foldGifts"));
        boolean sound = r.giftSound() != null ? r.giftSound() : Boolean.TRUE.equals(cur.get("giftSound"));
        String bg = r.background() != null && r.background().matches("[a-z0-9-]{1,20}") ? r.background() : (String) cur.get("background");
        jdbc.update("insert into parivar_member_prefs (parivar_id, user_id, muted, fold_gifts, gift_sound, background) values (?, ?, ?, ?, ?, ?)"
                + " on conflict (parivar_id, user_id) do update set muted = excluded.muted, fold_gifts = excluded.fold_gifts, gift_sound = excluded.gift_sound, background = excluded.background", parivarId, userId, muted, fold, sound, bg);
        return prefs(userId, parivarId);
    }
}
