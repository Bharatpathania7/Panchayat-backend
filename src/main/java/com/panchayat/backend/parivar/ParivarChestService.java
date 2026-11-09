package com.panchayat.backend.parivar;

import com.panchayat.backend.admin.media.MediaUrls;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Chests. The Parivar gets chests from the shop (inventory) or buys one straight from the treasury, sends it to its members,
 * and each member claims one piece (what is inside is picked on the server from the chest's reward pool). A chest that nobody
 * finishes expires; what happens to the unclaimed pieces is the admin's setting (nothing, or their share of the price back to the treasury).
 */
@Service
public class ParivarChestService {

    private static final Logger log = LoggerFactory.getLogger(ParivarChestService.class);

    private final JdbcTemplate jdbc;
    private final ParivarService core;
    private final ParivarEconomyService economy;
    private final TransactionTemplate tx;
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "parivar-chests");
        t.setDaemon(true);
        return t;
    });

    public ParivarChestService(JdbcTemplate jdbc, ParivarService core, ParivarEconomyService economy, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.core = core;
        this.economy = economy;
        this.tx = tx;
        ticker.scheduleWithFixedDelay(this::expire, 120, 300, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        ticker.shutdownNow();
    }

    private static long l(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }

    /** The chest types the Parivar can send (with how many it has in its inventory) and the chests sent recently. */
    public Map<String, Object> chests(Long userId, Long parivarId) {
        Map<String, Object> p = core.activeParivar(parivarId);
        if (core.roleOf(parivarId, userId) == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only members can see chests.");
        int level = ((Number) p.get("level")).intValue();
        List<Map<String, Object>> types = jdbc.query("select t.*, coalesce((select qty from parivar_inventory i where i.parivar_id = ? and i.kind = 'CHEST' and i.ref = t.id), 0) as owned from parivar_chest_types t where t.active and t.min_level <= ? order by t.sort_order, t.id",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", String.valueOf(rs.getLong("id")));
                    m.put("name", rs.getString("name"));
                    m.put("imageUrl", MediaUrls.link(rs.getString("image_url")));
                    m.put("priceFunds", rs.getLong("price_funds"));
                    m.put("pieces", rs.getInt("pieces"));
                    m.put("owned", rs.getLong("owned"));
                    return m;
                }, parivarId, level);
        List<Map<String, Object>> sent = jdbc.query("select c.id, c.name, c.pieces, c.claimed, c.status, c.expires_at, c.created_at, u.full_name,"
                        + " exists (select 1 from parivar_chest_claims k where k.chest_id = c.id and k.user_id = ?) as mine from parivar_chests c left join users u on u.id = c.sent_by where c.parivar_id = ? order by c.id desc limit 15",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", String.valueOf(rs.getLong("id")));
                    m.put("name", rs.getString("name"));
                    m.put("pieces", rs.getInt("pieces"));
                    m.put("claimed", rs.getInt("claimed"));
                    m.put("status", rs.getString("status"));
                    m.put("expiresAt", rs.getTimestamp("expires_at").toInstant().toString());
                    m.put("sentBy", rs.getString("full_name"));
                    m.put("claimedByMe", rs.getBoolean("mine"));
                    return m;
                }, userId, parivarId);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("types", types);
        res.put("sent", sent);
        res.put("canSend", core.can(parivarId, userId, "MANAGE_CHESTS"));
        res.put("funds", l(p.get("funds")));
        return res;
    }

    @Transactional
    public Map<String, Object> send(Long userId, Long parivarId, Long typeId, String key) {
        if (key == null || key.isBlank() || key.length() > 60) throw ParivarService.bad("A request key is required.");
        Map<String, Object> p = core.activeParivar(parivarId);
        core.require(parivarId, userId, "MANAGE_CHESTS");
        List<Map<String, Object>> tl = jdbc.queryForList("select * from parivar_chest_types where id = ? and active", typeId);
        if (tl.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That chest isn't available.");
        Map<String, Object> t = tl.get(0);
        if (((Number) p.get("level")).intValue() < ((Number) t.get("min_level")).intValue()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Your Parivar needs level " + t.get("min_level") + " for this chest.");
        String ref = "chestsend:" + parivarId + ":" + userId + ":" + key.strip();
        // A retry of a send that already worked: nothing more is taken
        if (l(jdbc.queryForObject("select count(*) from parivar_chests where send_key = ?", Long.class, ref)) > 0) return Map.of("ok", true);
        // One from the inventory if there is one, else bought now with the treasury
        long price = 0;
        if (!economy.takeInventory(parivarId, "CHEST", typeId, 1)) {
            price = l(t.get("price_funds"));
            if (price > 0) core.changeFunds(parivarId, userId, -price, "CHEST", ref);
        }
        long hours = l(core.settings().get("chest_expiry_hours"));
        Timestamp expires = Timestamp.from(Instant.now().plus(Duration.ofHours(Math.max(1, hours))));
        jdbc.update("insert into parivar_chests (parivar_id, type_id, name, price, pieces, sent_by, expires_at, send_key) values (?, ?, ?, ?, ?, ?, ?, ?)", parivarId, typeId, t.get("name"), price, t.get("pieces"), userId, expires, ref);
        core.system(parivarId, "🎁 A " + t.get("name") + " was sent to the Parivar. Open Chests to claim your piece!");
        return Map.of("ok", true);
    }

    @Transactional
    public Map<String, Object> claim(Long userId, Long parivarId, Long chestId) {
        core.activeParivar(parivarId);
        if (core.roleOf(parivarId, userId) == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only members can claim.");
        List<Map<String, Object>> cl = jdbc.queryForList("select * from parivar_chests where id = ? and parivar_id = ? for update", chestId, parivarId);
        if (cl.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That chest doesn't exist.");
        Map<String, Object> c = cl.get(0);
        if (!"OPEN".equals(c.get("status")) || ((Timestamp) c.get("expires_at")).toInstant().isBefore(Instant.now())) throw new ResponseStatusException(HttpStatus.CONFLICT, "This chest has closed.");
        if (((Number) c.get("claimed")).intValue() >= ((Number) c.get("pieces")).intValue()) throw new ResponseStatusException(HttpStatus.CONFLICT, "All the pieces were claimed.");
        if (l(jdbc.queryForObject("select count(*) from parivar_chest_claims where chest_id = ? and user_id = ?", Long.class, chestId, userId)) > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "You've already claimed your piece.");
        List<Map<String, Object>> pool = jdbc.queryForList("select kind, ref, amount, weight, label from parivar_chest_rewards where chest_id = ? and active", c.get("type_id"));
        Map<String, Object> r = economy.pickWeighted(pool);
        economy.applyReward(parivarId, userId, (String) r.get("kind"), l(r.get("ref")), l(r.get("amount")), "chest:" + chestId + ":" + userId, "Parivar chest: " + r.get("label"));
        jdbc.update("insert into parivar_chest_claims (chest_id, user_id, kind, amount, label) values (?, ?, ?, ?, ?)", chestId, userId, r.get("kind"), l(r.get("amount")), r.get("label"));
        int claimed = ((Number) c.get("claimed")).intValue() + 1;
        jdbc.update("update parivar_chests set claimed = ?, status = ? where id = ?", claimed, claimed >= ((Number) c.get("pieces")).intValue() ? "DONE" : "OPEN", chestId);
        return Map.of("kind", r.get("kind"), "amount", l(r.get("amount")), "label", r.get("label"));
    }

    /** Closes chests whose time is up and, if the admin chose so, sends the unclaimed pieces' share of the price back to the treasury. */
    void expire() {
        try {
            List<Map<String, Object>> due = jdbc.queryForList("select id from parivar_chests where status = 'OPEN' and expires_at <= now() limit 100");
            String policy = (String) core.settings().get("chest_expiry_policy");
            for (Map<String, Object> d : due) {
                long id = l(d.get("id"));
                try {
                    tx.executeWithoutResult(s -> {
                        List<Map<String, Object>> cl = jdbc.queryForList("select * from parivar_chests where id = ? and status = 'OPEN' for update", id);
                        if (cl.isEmpty()) return;
                        Map<String, Object> c = cl.get(0);
                        jdbc.update("update parivar_chests set status = 'EXPIRED' where id = ?", id);
                        int left = ((Number) c.get("pieces")).intValue() - ((Number) c.get("claimed")).intValue();
                        long back = "REFUND_FUNDS".equals(policy) ? l(c.get("price")) * left / Math.max(1, ((Number) c.get("pieces")).intValue()) : 0;
                        if (back > 0) {
                            core.changeFunds(((Number) c.get("parivar_id")).longValue(), null, back, "CHESTBACK", "chestexpiry:" + id);
                            core.system(((Number) c.get("parivar_id")).longValue(), "⏳ A " + c.get("name") + " expired. " + back + " funds went back to the treasury.");
                        }
                    });
                } catch (Exception e) {
                    log.debug("Chest {} expiry failed: {}", id, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.debug("Chest expiry job failed: {}", e.getMessage());
        }
    }
}
