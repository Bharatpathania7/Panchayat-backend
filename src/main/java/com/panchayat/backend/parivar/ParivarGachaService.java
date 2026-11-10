package com.panchayat.backend.parivar;

import com.panchayat.backend.admin.media.MediaUrls;
import com.panchayat.backend.wallet.service.CoinWalletService;
import com.panchayat.backend.wallet.service.WalletPushService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * The Parivar Gacha machine. The result of every roll is picked here on the server with secure randomness, from the pool the
 * admin set; the app only shows what comes back. A roll costs one free spin (if the machine has them), else one Gacha Card from
 * the Parivar inventory, else the machine's price from the treasury, plus an optional coin fee from the person's own wallet.
 */
@Service
public class ParivarGachaService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final JdbcTemplate jdbc;
    private final ParivarService core;
    private final ParivarEconomyService economy;
    private final CoinWalletService wallet;
    private final WalletPushService walletPush;
    private final com.panchayat.backend.rewards.service.SpendRewardService bag;

    public ParivarGachaService(JdbcTemplate jdbc, ParivarService core, ParivarEconomyService economy, CoinWalletService wallet, WalletPushService walletPush,
                               com.panchayat.backend.rewards.service.SpendRewardService bag) {
        this.bag = bag;
        this.jdbc = jdbc;
        this.core = core;
        this.economy = economy;
        this.wallet = wallet;
        this.walletPush = walletPush;
    }

    private static long l(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }

    private Timestamp dayStart() {
        return Timestamp.from(LocalDate.now(IST).atStartOfDay(IST).toInstant());
    }

    private void on() {
        Boolean on = jdbc.query("select gacha_enabled from parivar_settings where id = 1", rs -> rs.next() ? rs.getBoolean(1) : false);
        if (!Boolean.TRUE.equals(on)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Gacha is switched off right now.");
    }

    /** The machines with their rewards and the real chance of each reward (weight / total weight). */
    public Map<String, Object> machines(Long userId, Long parivarId) {
        Map<String, Object> p = core.activeParivar(parivarId);
        if (core.roleOf(parivarId, userId) == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only members can use Gacha.");
        on();
        int level = ((Number) p.get("level")).intValue();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> m : jdbc.queryForList("select * from parivar_gacha_machines where active order by sort_order, id")) {
            long id = l(m.get("id"));
            List<Map<String, Object>> rewards = jdbc.queryForList("select id, kind, ref, amount, weight, rarity, label, image_url, gift_id from parivar_gacha_rewards where machine_id = ? and active order by weight desc, id", id);
            long total = rewards.stream().mapToLong(r -> l(r.get("weight"))).sum();
            List<Map<String, Object>> shown = new ArrayList<>();
            for (Map<String, Object> r : rewards) {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("kind", r.get("kind"));
                x.put("amount", l(r.get("amount")));
                x.put("rarity", r.get("rarity"));
                x.put("label", r.get("label"));
                x.put("imageUrl", MediaUrls.link((String) r.get("image_url")));
                x.put("giftId", r.get("gift_id"));
                x.put("chance", total == 0 ? 0 : Math.round(l(r.get("weight")) * 10000.0 / total) / 100.0);
                shown.add(x);
            }
            long usedFree = l(jdbc.queryForObject("select count(*) from parivar_gacha_log where parivar_id = ? and user_id = ? and machine_id = ? and paid_with = 'FREE' and created_at >= ?", Long.class, parivarId, userId, id, dayStart()));
            long cards = l(jdbc.query("select qty from parivar_inventory where parivar_id = ? and kind = 'GACHA_CARD' and ref = ?", rs -> rs.next() ? rs.getLong(1) : 0L, parivarId, id));
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", String.valueOf(id));
            o.put("name", m.get("name"));
            o.put("tier", m.get("tier"));
            o.put("imageUrl", MediaUrls.link((String) m.get("image_url")));
            o.put("animationUrl", MediaUrls.link((String) m.get("animation_url")));
            o.put("priceFunds", l(m.get("price_funds")));
            o.put("serviceFeeCoins", l(m.get("service_fee_coins")));
            o.put("freeFundCost", l(m.get("free_fund_cost")));
            o.put("rollPriceCoins", l(m.get("roll_price_coins")));
            o.put("freePerDay", l(m.get("free_per_day")));
            o.put("freeLeft", Math.max(0, l(m.get("free_per_day")) - usedFree));
            o.put("minLevel", l(m.get("min_level")));
            o.put("locked", level < ((Number) m.get("min_level")).intValue());
            o.put("cards", cards);
            o.put("rewards", shown);
            out.add(o);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("machines", out);
        res.put("funds", l(p.get("funds")));
        // Anyone in the Parivar can roll: the free roll comes from the fund, the rest is paid with their own tickets or gold
        res.put("canRoll", true);
        res.put("tickets", economy.tickets(userId));
        res.put("ticketPrice", ticketPrice());
        return res;
    }

    @Transactional
    public Map<String, Object> roll(Long userId, Long parivarId, Long machineId, int times, String key) {
        if (key == null || key.isBlank() || key.length() > 60) throw ParivarService.bad("A request key is required.");
        if (times != 1 && times != 10) throw ParivarService.bad("You can roll 1 or 10 times.");
        Map<String, Object> p = core.activeParivar(parivarId);
        if (core.roleOf(parivarId, userId) == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only members can use Gacha.");
        on();
        core.lockUser(userId);
        List<Map<String, Object>> ml = jdbc.queryForList("select * from parivar_gacha_machines where id = ? and active", machineId);
        if (ml.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That machine isn't available.");
        Map<String, Object> machine = ml.get(0);
        if (((Number) p.get("level")).intValue() < ((Number) machine.get("min_level")).intValue()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Your Parivar needs level " + machine.get("min_level") + " for this machine.");

        String base = "gacha:" + userId + ":" + key.strip();
        // A retry of a roll that already worked: the same results, nothing more taken
        List<Map<String, Object>> done = jdbc.queryForList("select kind, amount, label, rarity, paid_with from parivar_gacha_log where ref like ? order by id", base + ":%");
        if (!done.isEmpty()) return result(done, parivarId);

        List<Map<String, Object>> pool = jdbc.queryForList("select id, kind, ref, amount, weight, rarity, label, gift_id from parivar_gacha_rewards where machine_id = ? and active", machineId);
        long freeUsed = l(jdbc.queryForObject("select count(*) from parivar_gacha_log where parivar_id = ? and user_id = ? and machine_id = ? and paid_with = 'FREE' and created_at >= ?", Long.class, parivarId, userId, machineId, dayStart()));
        long freeLeft = Math.max(0, l(machine.get("free_per_day")) - freeUsed);
        long freeCost = l(machine.get("free_fund_cost"));
        long goldPrice = l(machine.get("roll_price_coins"));
        long fundsLeft = l(p.get("funds"));
        long tickets = economy.tickets(userId);
        long fundsNeeded = 0, goldNeeded = 0, ticketsNeeded = 0;
        List<String> paid = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            if (freeLeft > 0 && fundsLeft - fundsNeeded >= freeCost) {
                // The daily free roll: the Parivar fund pays the machine's fee
                freeLeft--;
                fundsNeeded += freeCost;
                paid.add("FREE");
            } else if (tickets - ticketsNeeded > 0) {
                ticketsNeeded++;
                paid.add("CARD");
            } else {
                goldNeeded += goldPrice;
                paid.add("GOLD");
            }
        }
        // Take the payment first (in this one transaction); any shortage cancels the whole roll
        if (ticketsNeeded > 0 && !economy.takeTickets(userId, ticketsNeeded)) throw new ResponseStatusException(HttpStatus.CONFLICT, "You don't have enough Gacha tickets.");
        if (fundsNeeded > 0) core.changeFunds(parivarId, userId, -fundsNeeded, "GACHA", base);
        if (goldNeeded > 0) {
            try {
                wallet.debit(userId, goldNeeded, "PARIVAR_GACHA", "parivar:" + parivarId, "pv-" + base, "Gacha rolls");
            } catch (IllegalStateException e) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Not enough gold. A roll costs " + goldPrice + " gold, or use a Gacha ticket.");
            }
            walletPush.pushBalance(userId, "PARIVAR_GACHA", -goldNeeded);
        }

        List<Map<String, Object>> results = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            Map<String, Object> r = economy.pickWeighted(pool);
            String ref = base + ":" + i;
            String kind = (String) r.get("kind");
            if ("GIFT_CARD".equals(kind)) {
                if (r.get("gift_id") != null) bag.grantGachaGift(userId, (String) r.get("gift_id"), (int) Math.max(1, l(r.get("amount"))), 30);
            } else if ("ITEM".equals(kind)) {
                bag.grantGachaItem(userId, l(r.get("ref")), (int) l(r.get("amount")));
            } else {
                economy.applyReward(parivarId, userId, kind, l(r.get("ref")), l(r.get("amount")), ref, "Gacha win: " + r.get("label"));
            }
            jdbc.update("insert into parivar_gacha_log (parivar_id, user_id, machine_id, reward_id, kind, amount, label, rarity, paid_with, ref) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    parivarId, userId, machineId, r.get("id"), r.get("kind"), l(r.get("amount")), r.get("label"), r.get("rarity"), paid.get(i), ref);
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("kind", r.get("kind"));
            x.put("amount", l(r.get("amount")));
            x.put("label", r.get("label"));
            x.put("rarity", r.get("rarity"));
            x.put("paidWith", paid.get(i));
            results.add(x);
            if ("LEGENDARY".equals(r.get("rarity")) || "EPIC".equals(r.get("rarity"))) core.system(parivarId, "🎰 " + name(userId) + " won " + r.get("label") + " from " + machine.get("name") + "!");
        }
        jdbc.update("insert into parivar_activity (parivar_id, user_id, kind, exp, label, qty, ref) values (?, ?, 'GACHA', 0, ?, ?, ?)", parivarId, userId, "rolled " + machine.get("name") + (times > 1 ? " x" + times : ""), times, "gacharoll:" + base);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("results", results);
        res.put("funds", l(core.activeParivar(parivarId).get("funds")));
        return res;
    }

    long ticketPrice() {
        return l(jdbc.query("select gacha_ticket_price from parivar_settings where id = 1", rs -> rs.next() ? rs.getLong(1) : 1000L));
    }

    /** Buy Gacha tickets with your own gold (the price is set in the admin panel). */
    @Transactional
    public Map<String, Object> buyTickets(Long userId, Long parivarId, int qty) {
        core.activeParivar(parivarId);
        if (core.roleOf(parivarId, userId) == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only members can use Gacha.");
        on();
        if (qty < 1 || qty > 100) throw ParivarService.bad("Buy between 1 and 100 tickets.");
        core.lockUser(userId);
        long total = Math.multiplyExact(ticketPrice(), (long) qty);
        if (total > 0) {
            try {
                wallet.debit(userId, total, "PARIVAR_GACHA", "parivar:" + parivarId, "pv-ticket:" + userId + ":" + System.nanoTime(), "Gacha tickets");
            } catch (IllegalStateException e) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Not enough gold. " + qty + " ticket(s) cost " + total + " gold.");
            }
            walletPush.pushBalance(userId, "PARIVAR_GACHA", -total);
        }
        economy.addTickets(userId, qty);
        return Map.of("tickets", economy.tickets(userId), "spent", total);
    }

    private String name(Long userId) {
        return jdbc.query("select coalesce(full_name, 'Someone') from users where id = ?", rs -> rs.next() ? rs.getString(1) : "Someone", userId);
    }

    private Map<String, Object> result(List<Map<String, Object>> rows, Long parivarId) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("kind", r.get("kind"));
            x.put("amount", l(r.get("amount")));
            x.put("label", r.get("label"));
            x.put("rarity", r.get("rarity"));
            x.put("paidWith", r.get("paid_with"));
            results.add(x);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("results", results);
        res.put("funds", l(core.activeParivar(parivarId).get("funds")));
        return res;
    }

    public List<Map<String, Object>> history(Long userId, Long parivarId) {
        core.activeParivar(parivarId);
        if (core.roleOf(parivarId, userId) == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only members can see this.");
        return jdbc.query("select g.id, g.kind, g.amount, g.label, g.rarity, g.paid_with, g.created_at, u.full_name, m.name as machine from parivar_gacha_log g"
                        + " left join users u on u.id = g.user_id left join parivar_gacha_machines m on m.id = g.machine_id where g.parivar_id = ? order by g.id desc limit 40",
                (rs, i) -> {
                    Map<String, Object> x = new LinkedHashMap<>();
                    x.put("id", String.valueOf(rs.getLong("id")));
                    x.put("who", rs.getString("full_name"));
                    x.put("machine", rs.getString("machine"));
                    x.put("label", rs.getString("label"));
                    x.put("rarity", rs.getString("rarity"));
                    x.put("paidWith", rs.getString("paid_with"));
                    x.put("at", rs.getTimestamp("created_at").toInstant().toString());
                    return x;
                }, parivarId);
    }
}
