package com.panchayat.backend.parivar;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.panchayat.backend.admin.media.MediaUrls;
import com.panchayat.backend.auth.dto.AdminVipGrantRequest;
import com.panchayat.backend.auth.service.AdminVipService;
import com.panchayat.backend.rewards.service.SpendRewardService;
import com.panchayat.backend.wallet.service.CoinWalletService;
import com.panchayat.backend.wallet.service.DiamondService;
import com.panchayat.backend.wallet.service.WalletPushService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Family Treasure. The Parivar's weekly activity earns a chest per tier (the weekly grant is in ParivarService). Any member claims
 * it into the Treasure, a timer runs, then only the Leader or a Deputy sends it: to everyone by luck, to one member, or takes it all.
 *
 * Every number comes from treasure_tiers / treasure_rewards (the admin panel) and every draw is made here on the server with a
 * SecureRandom. The coin and diamond pools are split so the parts add up to exactly the pool (the remainder goes one by one to members
 * picked at random), and each item can only be drawn up to its limit. The chest row is locked, so a chest can be opened only once.
 */
@Service
public class ParivarTreasureService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<String, Integer> POINTS = Map.of("COMMON", 1, "PREMIUM", 5, "EPIC", 25, "LEGENDARY", 100, "ULTRA_RARE", 400);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final ParivarService core;
    private final CoinWalletService wallet;
    private final WalletPushService walletPush;
    private final DiamondService diamonds;
    private final SpendRewardService bag;
    private final AdminVipService vip;
    private final com.panchayat.backend.titles.ProfileTitleService profileTitles;

    public ParivarTreasureService(JdbcTemplate jdbc, ParivarService core, CoinWalletService wallet, WalletPushService walletPush, DiamondService diamonds,
                                  SpendRewardService bag, AdminVipService vip, com.panchayat.backend.titles.ProfileTitleService profileTitles) {
        this.profileTitles = profileTitles;
        this.jdbc = jdbc;
        this.core = core;
        this.wallet = wallet;
        this.walletPush = walletPush;
        this.diamonds = diamonds;
        this.bag = bag;
        this.vip = vip;
    }

    private static long l(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }

    private static String iso(Object ts) {
        return ts == null ? null : ((Timestamp) ts).toInstant().toString();
    }

    private void audit(Long chestId, Long parivarId, Long userId, String action, String detail) {
        jdbc.update("insert into treasure_audit (chest_id, parivar_id, user_id, action, detail) values (?, ?, ?, ?, ?)", chestId, parivarId, userId, action, detail);
    }

    /* ======================= the screen ======================= */

    public Map<String, Object> state(Long userId, Long parivarId) {
        core.activeParivar(parivarId);
        String role = core.roleOf(parivarId, userId);
        if (role == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only members can see the Treasure.");
        boolean canSend = "LEADER".equals(role) || "DEPUTY".equals(role);
        long week = l(jdbc.queryForObject("select coalesce(sum(exp), 0) from parivar_activity where parivar_id = ? and created_at >= ?", Long.class, parivarId, core.weekStart()));
        String wk = core.weekStart().toInstant().toString().substring(0, 10);
        List<Map<String, Object>> tiers = new ArrayList<>();
        for (Map<String, Object> t : jdbc.queryForList("select * from treasure_tiers order by sort_order, id")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", String.valueOf(t.get("id")));
            m.put("code", t.get("code"));
            m.put("name", t.get("name"));
            m.put("enabled", t.get("enabled"));
            m.put("target", l(t.get("weekly_target")));
            m.put("unlockMinutes", l(t.get("unlock_minutes")));
            m.put("poolCoins", l(t.get("pool_coins")));
            m.put("poolDiamonds", l(t.get("pool_diamonds")));
            m.put("imageUrl", MediaUrls.link((String) t.get("image_url")));
            m.put("earnedThisWeek", jdbc.queryForObject("select count(*) from treasure_chests where parivar_id = ? and tier_id = ? and grant_key like ?", Long.class, parivarId, t.get("id"), "wk:" + parivarId + ":" + wk + ":%") > 0);
            m.put("rewards", jdbc.query("select r.kind, r.rarity, r.weight, case when r.kind = 'COLLECTIBLE' then c.name when r.kind = 'PROFILE_TITLE' then pt.name else r.label end as label, coalesce(c.media_url, pt.icon_url, r.image_url) as image_url from treasure_rewards r"
                    + " left join parivar_collectibles c on r.kind = 'COLLECTIBLE' and c.id = r.ref left join profile_titles pt on r.kind = 'PROFILE_TITLE' and pt.id = r.ref"
                    + " where r.tier_id = ? and r.active and (r.kind <> 'COLLECTIBLE' or c.active) and (r.kind <> 'PROFILE_TITLE' or pt.active) order by r.weight desc, r.id",
                    (rs, i) -> Map.of("kind", rs.getString("kind"), "rarity", rs.getString("rarity"), "label", rs.getString("label"), "imageUrl", MediaUrls.link(rs.getString("image_url")) == null ? "" : MediaUrls.link(rs.getString("image_url"))), t.get("id")));
            tiers.add(m);
        }
        List<Map<String, Object>> chests = new ArrayList<>();
        for (Map<String, Object> c : jdbc.queryForList("select c.*, u.full_name as claimer from treasure_chests c left join users u on u.id = c.claimed_by where c.parivar_id = ? and c.status <> 'OPENED' order by c.id desc limit 30", parivarId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", String.valueOf(c.get("id")));
            m.put("tier", c.get("tier_code"));
            m.put("name", c.get("name"));
            m.put("status", c.get("status"));
            m.put("poolCoins", l(c.get("pool_coins")));
            m.put("poolDiamonds", l(c.get("pool_diamonds")));
            m.put("earnedAt", iso(c.get("earned_at")));
            m.put("claimedBy", c.get("claimer"));
            m.put("unlockAt", iso(c.get("unlock_at")));
            boolean treasure = "TREASURE".equals(c.get("status"));
            long left = treasure ? Math.max(0, Duration.between(Instant.now(), ((Timestamp) c.get("unlock_at")).toInstant()).getSeconds()) : 0;
            m.put("secondsLeft", left);
            m.put("ready", treasure && left == 0);
            chests.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("canSend", canSend);
        out.put("weekActivity", week);
        out.put("tiers", tiers);
        out.put("chests", chests);
        out.put("history", history(parivarId, 20));
        out.put("diamonds", diamonds.balance(userId));
        out.put("members", canSend ? eligible(parivarId, 0) : List.of());
        List<Long> pending = jdbc.queryForList("select d.id from treasure_distributions d where d.parivar_id = ? and d.created_at > now() - interval '15 minutes'"
                + " and not exists (select 1 from treasure_views v where v.distribution_id = d.id and v.user_id = ?)"
                + " and exists (select 1 from treasure_payouts p where p.distribution_id = d.id and p.user_id = ?) order by d.id desc limit 1", Long.class, parivarId, userId, userId);
        out.put("pendingReveal", pending.isEmpty() ? null : String.valueOf(pending.get(0)));
        return out;
    }

    private List<Map<String, Object>> eligible(Long parivarId, int minHours) {
        return jdbc.query("select m.user_id, m.role, u.full_name, u.avatar_url from parivar_members m join users u on u.id = m.user_id where m.parivar_id = ?"
                        + " and m.joined_at <= now() - (? * interval '1 hour') order by case m.role when 'LEADER' then 0 when 'DEPUTY' then 1 else 2 end, u.full_name",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("userId", String.valueOf(rs.getLong("user_id")));
                    m.put("name", rs.getString("full_name"));
                    m.put("role", rs.getString("role"));
                    m.put("avatarUrl", MediaUrls.link(rs.getString("avatar_url")));
                    return m;
                }, parivarId, minHours);
    }

    private List<Map<String, Object>> history(Long parivarId, int limit) {
        return jdbc.query("select d.id, d.chest_name, d.tier_code, d.mode, d.created_at, d.member_count, d.total_coins, d.total_diamonds, u.full_name as sender, t.full_name as top"
                        + " from treasure_distributions d left join users u on u.id = d.sent_by left join users t on t.id = d.top_user where d.parivar_id = ? order by d.id desc limit ?",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", String.valueOf(rs.getLong("id")));
                    m.put("name", rs.getString("chest_name"));
                    m.put("tier", rs.getString("tier_code"));
                    m.put("mode", rs.getString("mode"));
                    m.put("at", rs.getTimestamp("created_at").toInstant().toString());
                    m.put("members", rs.getInt("member_count"));
                    m.put("coins", rs.getLong("total_coins"));
                    m.put("diamonds", rs.getLong("total_diamonds"));
                    m.put("sentBy", rs.getString("sender"));
                    m.put("top", rs.getString("top"));
                    return m;
                }, parivarId, limit);
    }

    /** One opening with every member's result: the same for everyone who is in the Parivar. */
    public Map<String, Object> distribution(Long userId, Long parivarId, Long distributionId) {
        if (core.roleOf(parivarId, userId) == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only members can see this.");
        List<Map<String, Object>> dl = jdbc.queryForList("select d.*, u.full_name as sender from treasure_distributions d left join users u on u.id = d.sent_by where d.id = ? and d.parivar_id = ?", distributionId, parivarId);
        if (dl.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That opening doesn't exist.");
        Map<String, Object> d = dl.get(0);
        List<Map<String, Object>> payouts = jdbc.query("select p.*, u.full_name, u.avatar_url from treasure_payouts p join users u on u.id = p.user_id where p.distribution_id = ? order by p.score desc, p.id",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("userId", String.valueOf(rs.getLong("user_id")));
                    m.put("name", rs.getString("full_name"));
                    m.put("avatarUrl", MediaUrls.link(rs.getString("avatar_url")));
                    m.put("coins", rs.getLong("coins"));
                    m.put("diamonds", rs.getLong("diamonds"));
                    m.put("items", parseItems(rs.getString("items")));
                    m.put("score", rs.getLong("score"));
                    return m;
                }, distributionId);
        // Everyone in the family who was part of it, even someone who got no payout row (they are not listed, only winners are)
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", String.valueOf(distributionId));
        out.put("name", d.get("chest_name"));
        out.put("tier", d.get("tier_code"));
        out.put("mode", d.get("mode"));
        out.put("sentBy", d.get("sender"));
        out.put("at", iso(d.get("created_at")));
        out.put("totalCoins", l(d.get("total_coins")));
        out.put("totalDiamonds", l(d.get("total_diamonds")));
        out.put("topUserId", d.get("top_user") == null ? null : String.valueOf(d.get("top_user")));
        out.put("payouts", payouts);
        return out;
    }

    private static List<Map<String, Object>> parseItems(String json) {
        try {
            return JSON.readValue(json, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    @Transactional
    public void seen(Long userId, Long parivarId, Long distributionId) {
        if (core.roleOf(parivarId, userId) == null) return;
        jdbc.update("insert into treasure_views (distribution_id, user_id) select ?, ? where exists (select 1 from treasure_distributions where id = ? and parivar_id = ?) on conflict do nothing", distributionId, userId, distributionId, parivarId);
    }

    /** What this person won from chests, newest first (shown in the profile), and their diamonds and titles / badges. */
    public Map<String, Object> mine(Long userId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("diamonds", diamonds.balance(userId));
        out.put("wins", jdbc.query("select p.coins, p.diamonds, p.items, p.created_at, d.chest_name, d.tier_code, d.mode from treasure_payouts p join treasure_distributions d on d.id = p.distribution_id where p.user_id = ? order by p.id desc limit 50",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("chest", rs.getString("chest_name"));
                    m.put("tier", rs.getString("tier_code"));
                    m.put("mode", rs.getString("mode"));
                    m.put("coins", rs.getLong("coins"));
                    m.put("diamonds", rs.getLong("diamonds"));
                    m.put("items", parseItems(rs.getString("items")));
                    m.put("at", rs.getTimestamp("created_at").toInstant().toString());
                    return m;
                }, userId));
        out.put("collection", jdbc.query("select kind, label, image_url, source, created_at from user_collection where user_id = ? order by id desc limit 100",
                (rs, i) -> Map.of("kind", rs.getString("kind"), "label", rs.getString("label"), "source", rs.getString("source"), "at", rs.getTimestamp("created_at").toInstant().toString(),
                        "imageUrl", MediaUrls.link(rs.getString("image_url")) == null ? "" : MediaUrls.link(rs.getString("image_url"))), userId));
        return out;
    }

    /* ======================= claim ======================= */

    @Transactional
    public Map<String, Object> claim(Long userId, Long parivarId, Long chestId) {
        core.activeParivar(parivarId);
        if (core.roleOf(parivarId, userId) == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only members can claim.");
        List<Map<String, Object>> cl = jdbc.queryForList("select * from treasure_chests where id = ? and parivar_id = ? for update", chestId, parivarId);
        if (cl.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That chest doesn't exist.");
        Map<String, Object> c = cl.get(0);
        if (!"AVAILABLE".equals(c.get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "This chest was already claimed.");
        Integer mins = jdbc.query("select unlock_minutes from treasure_tiers where id = ?", rs -> rs.next() ? rs.getInt(1) : 0, c.get("tier_id"));
        Instant unlock = Instant.now().plus(Duration.ofMinutes(mins == null ? 0 : mins));
        jdbc.update("update treasure_chests set status = 'TREASURE', claimed_by = ?, claimed_at = now(), unlock_at = ? where id = ?", userId, Timestamp.from(unlock), chestId);
        audit(chestId, parivarId, userId, "CLAIM", c.get("name") + " moved to the Treasure, unlocks " + unlock);
        core.system(parivarId, "🗝️ The " + c.get("name") + " was claimed and moved to the Family Treasure. It unlocks in " + mins + " min.");
        return Map.of("ok", true, "unlockAt", unlock.toString());
    }

    /* ======================= send ======================= */

    public record SendRequest(String mode, Long targetUserId) {
    }

    @Transactional
    public Map<String, Object> send(Long userId, Long parivarId, Long chestId, SendRequest req) {
        core.activeParivar(parivarId);
        String role = core.roleOf(parivarId, userId);
        if (!"LEADER".equals(role) && !"DEPUTY".equals(role)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the Leader and the Deputies can send the chest.");
        String mode = req == null || req.mode() == null ? "" : req.mode().toUpperCase(Locale.ROOT);
        if (!List.of("ALL", "TARGET", "TAKE_ALL").contains(mode)) throw ParivarService.bad("Choose how to send the chest.");
        List<Map<String, Object>> cl = jdbc.queryForList("select * from treasure_chests where id = ? and parivar_id = ? for update", chestId, parivarId);
        if (cl.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That chest doesn't exist.");
        Map<String, Object> c = cl.get(0);
        if (!"TREASURE".equals(c.get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "This chest can't be sent now.");
        if (((Timestamp) c.get("unlock_at")).toInstant().isAfter(Instant.now())) throw new ResponseStatusException(HttpStatus.CONFLICT, "The chest is still locked.");
        Map<String, Object> tier = jdbc.queryForList("select * from treasure_tiers where id = ?", c.get("tier_id")).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "This chest type was removed."));

        List<Long> members = new ArrayList<>();
        for (Map<String, Object> m : eligible(parivarId, (int) l(tier.get("eligible_min_hours")))) members.add(Long.valueOf((String) m.get("userId")));
        if (members.isEmpty()) members.add(userId);
        Long target = null;
        if ("TARGET".equals(mode)) {
            target = req.targetUserId();
            if (target == null || core.roleOf(parivarId, target) == null) throw ParivarService.bad("Choose a member of this Parivar.");
        } else if ("TAKE_ALL".equals(mode)) {
            target = userId;
        }

        Map<Long, Payout> plan = plan(tier, c, members);
        if (target != null) {
            Payout all = new Payout();
            for (Payout p : plan.values()) {
                all.coins += p.coins;
                all.diamonds += p.diamonds;
                all.items.addAll(p.items);
            }
            plan = new LinkedHashMap<>();
            plan.put(target, all);
        }

        // Safety: what is paid out can never be more than the chest holds
        long sumCoins = 0, sumDiamonds = 0;
        for (Payout p : plan.values()) {
            sumCoins += p.coins;
            sumDiamonds += p.diamonds;
        }
        if (sumCoins > l(c.get("pool_coins")) || sumDiamonds > l(c.get("pool_diamonds"))) throw new IllegalStateException("Treasure plan went over the pool");

        long bestScore = -1;
        Long top = null;
        for (Map.Entry<Long, Payout> e : plan.entrySet()) {
            Payout p = e.getValue();
            p.score = p.coins + p.diamonds * 50;
            for (Map<String, Object> it : p.items) p.score += POINTS.getOrDefault((String) it.get("rarity"), 1) * 1000L;
            if (p.score > bestScore) {
                bestScore = p.score;
                top = e.getKey();
            }
        }
        Long did = jdbc.queryForObject("insert into treasure_distributions (chest_id, parivar_id, tier_code, chest_name, mode, sent_by, member_count, total_coins, total_diamonds, top_user)"
                + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id", Long.class, chestId, parivarId, c.get("tier_code"), c.get("name"), mode, userId, plan.size(), sumCoins, sumDiamonds, plan.size() > 1 ? top : target);
        for (Map.Entry<Long, Payout> e : plan.entrySet()) {
            Payout p = e.getValue();
            String items;
            try {
                items = JSON.writeValueAsString(p.items);
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
            jdbc.update("insert into treasure_payouts (distribution_id, chest_id, parivar_id, user_id, coins, diamonds, items, score) values (?, ?, ?, ?, ?, ?, ?, ?)", did, chestId, parivarId, e.getKey(), p.coins, p.diamonds, items, p.score);
            grant(chestId, e.getKey(), p);
        }
        jdbc.update("update treasure_chests set status = 'OPENED', opened_at = now() where id = ?", chestId);
        audit(chestId, parivarId, userId, "SEND_" + mode, plan.size() + " people, " + sumCoins + " coins, " + sumDiamonds + " diamonds" + (target != null ? ", to user " + target : ""));
        String sender = jdbc.queryForObject("select full_name from users where id = ?", String.class, userId);
        core.system(parivarId, "🎉 " + sender + " opened the " + c.get("name") + "! " + ("ALL".equals(mode) ? "Everyone has a reward waiting - open Treasure to watch." : "See the Treasure for what was inside."));
        String pname = jdbc.queryForObject("select name from parivars where id = ?", String.class, parivarId);
        core.world("CHEST", parivarId, null, "🎁 " + pname + " opened a " + c.get("name") + " (" + sumCoins + " coins" + (sumDiamonds > 0 ? ", " + sumDiamonds + " diamonds" : "") + ")", sumCoins);
        return Map.of("ok", true, "distributionId", String.valueOf(did));
    }

    private static final class Payout {
        long coins;
        long diamonds;
        long score;
        final List<Map<String, Object>> items = new ArrayList<>();
    }

    /** Builds who gets what. Nothing is written here. */
    private Map<Long, Payout> plan(Map<String, Object> tier, Map<String, Object> chest, List<Long> members) {
        int n = members.size();
        List<Long> order = new ArrayList<>(members);
        Collections.shuffle(order, RANDOM);
        Map<Long, Payout> out = new LinkedHashMap<>();
        for (Long m : members) out.put(m, new Payout());
        long[] coins = split(l(chest.get("pool_coins")), order.size());
        long[] dia = split(l(chest.get("pool_diamonds")), order.size());
        for (int i = 0; i < n; i++) {
            out.get(order.get(i)).coins = coins[i];
            out.get(order.get(i)).diamonds = dia[i];
        }
        List<Map<String, Object>> rewards = new ArrayList<>(jdbc.queryForList("select r.*, c.name as cname, c.media_url as cmedia, c.kind as ckind, pt.name as tname, pt.icon_url as ticon from treasure_rewards r left join parivar_collectibles c on r.kind = 'COLLECTIBLE' and c.id = r.ref"
                + " left join profile_titles pt on r.kind = 'PROFILE_TITLE' and pt.id = r.ref"
                + " where r.tier_id = ? and r.active and r.weight > 0 and (r.kind <> 'COLLECTIBLE' or c.active) and (r.kind <> 'PROFILE_TITLE' or pt.active)", tier.get("id")));
        Map<Object, Integer> drawn = new HashMap<>();
        int per = (int) l(tier.get("draws_per_member"));
        for (int round = 0; round < per; round++) {
            for (Long m : order) {
                List<Map<String, Object>> open = new ArrayList<>();
                for (Map<String, Object> r : rewards) {
                    int max = (int) l(r.get("max_per_chest"));
                    if (max == 0 || drawn.getOrDefault(r.get("id"), 0) < max) open.add(r);
                }
                if (open.isEmpty()) break;
                long total = 0;
                for (Map<String, Object> r : open) total += l(r.get("weight"));
                long roll = (long) (RANDOM.nextDouble() * total), acc = 0;
                Map<String, Object> pick = open.get(open.size() - 1);
                for (Map<String, Object> r : open) {
                    acc += l(r.get("weight"));
                    if (roll < acc) {
                        pick = r;
                        break;
                    }
                }
                drawn.merge(pick.get("id"), 1, Integer::sum);
                Map<String, Object> it = new LinkedHashMap<>();
                it.put("rewardId", l(pick.get("id")));
                it.put("kind", pick.get("kind"));
                it.put("label", "COLLECTIBLE".equals(pick.get("kind")) ? pick.get("cname") : "PROFILE_TITLE".equals(pick.get("kind")) ? pick.get("tname") : pick.get("label"));
                if ("COLLECTIBLE".equals(pick.get("kind"))) it.put("collectibleKind", pick.get("ckind"));
                it.put("rarity", pick.get("rarity"));
                it.put("amount", l(pick.get("amount")));
                it.put("ref", l(pick.get("ref")));
                it.put("giftId", pick.get("gift_id"));
                String img = MediaUrls.link((String) ("COLLECTIBLE".equals(pick.get("kind")) ? pick.get("cmedia") : "PROFILE_TITLE".equals(pick.get("kind")) ? pick.get("ticon") : pick.get("image_url")));
                it.put("imageUrl", img == null ? "" : img);
                out.get(m).items.add(it);
            }
        }
        return out;
    }

    /**
     * Splits a pool between n people by luck. Each person gets a random weight between 0.5 and 1.5, so nobody can take the whole
     * pool (with 2 people the biggest share is at most 75 %). Every part is rounded down; the coins left over (fewer than n) go one
     * each to people picked at random, so the parts always add up to exactly the pool.
     */
    static long[] split(long pool, int n) {
        long[] out = new long[n];
        if (n <= 0 || pool <= 0) return out;
        if (n == 1) {
            out[0] = pool;
            return out;
        }
        double[] w = new double[n];
        double sum = 0;
        for (int i = 0; i < n; i++) {
            w[i] = 0.5 + RANDOM.nextDouble();
            sum += w[i];
        }
        long given = 0;
        for (int i = 0; i < n; i++) {
            out[i] = (long) Math.floor(pool * (w[i] / sum));
            given += out[i];
        }
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < n; i++) idx.add(i);
        Collections.shuffle(idx, RANDOM);
        long left = pool - given;
        for (int k = 0; left > 0; k = (k + 1) % n) {
            out[idx.get(k)]++;
            left--;
        }
        return out;
    }

    /** Puts a payout into the wallet, the Diamond wallet, the bag, the VIP and the profile collection. Each credit has its own once-only key. */
    private void grant(Long chestId, Long userId, Payout p) {
        String base = "treasure:" + chestId + ":" + userId;
        if (p.coins > 0) {
            wallet.credit(userId, p.coins, "PARIVAR_TREASURE", "treasure:" + chestId, base + ":coins", "Family Treasure chest");
            walletPush.pushBalance(userId, "PARIVAR_TREASURE", p.coins);
        }
        if (p.diamonds > 0) diamonds.credit(userId, p.diamonds, "PARIVAR_TREASURE", base + ":dia");
        int idx = 0;
        for (Map<String, Object> it : p.items) {
            String kind = (String) it.get("kind");
            long amount = l(it.get("amount"));
            String label = (String) it.get("label");
            idx++;
            switch (kind) {
                case "GIFT" -> {
                    if (it.get("giftId") != null) bag.grantGachaGift(userId, (String) it.get("giftId"), (int) Math.max(1, amount), 0);
                }
                case "ITEM" -> bag.grantGachaItem(userId, l(it.get("ref")), (int) amount);
                case "VIP_CARD" -> vip.grantVip(userId, new AdminVipGrantRequest((int) Math.max(1, amount)));
                case "PROFILE_TITLE" -> profileTitles.grant(userId, l(it.get("ref")), "TREASURE", 0);
                case "COLLECTIBLE" -> {
                    List<Map<String, Object>> cr = jdbc.queryForList("select kind, name, media_url from parivar_collectibles where id = ?", l(it.get("ref")));
                    if (cr.isEmpty()) throw new IllegalStateException("A treasure collectible was removed");
                    jdbc.update("insert into user_collection (user_id, kind, label, image_url, source, ref) values (?, ?, ?, ?, 'TREASURE', ?) on conflict do nothing",
                            userId, cr.get(0).get("kind"), cr.get(0).get("name"), cr.get(0).get("media_url"), base + ":" + idx);
                }
                case "TITLE", "BADGE" -> jdbc.update("insert into user_collection (user_id, kind, label, image_url, source, ref) values (?, ?, ?, ?, 'TREASURE', ?) on conflict do nothing",
                        userId, kind, label, ((String) it.get("imageUrl")).isEmpty() ? null : it.get("imageUrl"), base + ":" + idx);
                default -> throw new IllegalStateException("Unknown treasure reward " + kind);
            }
        }
    }
}
