package com.panchayat.backend.parivar;

import com.panchayat.backend.wallet.service.CoinWalletService;
import com.panchayat.backend.wallet.service.WalletPushService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Red packets for the family, and ban / unban for the Mukhiya.
 *  - The Mukhiya sends a red packet: N coins in P pieces, from the Mukhiya's own wallet or from the Parivar treasury, split equally or at random.
 *    Every member can open one piece. A packet closes after 24 hours (or when all pieces are taken) and what is left goes back to where it came from.
 *  - The Mukhiya (or whoever may remove members) can ban someone: they leave the Parivar and can't join or apply again until unbanned.
 */
@Service
public class ParivarRedPacketService {

    static final long MAX_COINS = 10_000_000;
    static final int MAX_PIECES = 100;
    static final Duration LIFETIME = Duration.ofHours(24);

    private final JdbcTemplate jdbc;
    private final ParivarService core;
    @Autowired @Lazy private CoinWalletService wallet;
    @Autowired @Lazy private WalletPushService walletPush;
    @Autowired @Lazy private org.springframework.messaging.simp.SimpMessagingTemplate messaging;
    @Autowired @Lazy private com.panchayat.backend.push.PushService push;
    @Autowired @Lazy private ParivarEconomyService economy;

    public ParivarRedPacketService(JdbcTemplate jdbc, ParivarService core) {
        this.jdbc = jdbc;
        this.core = core;
    }

    private static long l(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }

    private static ResponseStatusException conflict(String m) {
        return new ResponseStatusException(HttpStatus.CONFLICT, m);
    }

    private void member(Long parivarId, Long userId) {
        core.activeParivar(parivarId);
        if (core.roleOf(parivarId, userId) == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only members can see the red packets.");
    }

    /* ---------------- red packets ---------------- */

    public Map<String, Object> list(Long userId, Long parivarId) {
        member(parivarId, userId);
        expireDue();
        List<Map<String, Object>> rows = jdbc.query("select r.*, u.full_name, exists (select 1 from parivar_redpacket_claims c where c.packet_id = r.id and c.user_id = ?) as mine,"
                        + " (select c.coins from parivar_redpacket_claims c where c.packet_id = r.id and c.user_id = ?) as my_coins"
                        + " from parivar_redpackets r left join users u on u.id = r.sender_id where r.parivar_id = ? and r.created_at > now() - interval '3 days' order by r.id desc limit 20",
                (rs, i2) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    long id = rs.getLong("id");
                    m.put("id", String.valueOf(id));
                    m.put("sender", "ADMIN".equals(rs.getString("source")) ? "Panchayat" : rs.getString("full_name"));
                    m.put("total", rs.getLong("total"));
                    m.put("pieces", rs.getInt("pieces"));
                    m.put("claimed", rs.getInt("claimed"));
                    m.put("status", rs.getString("status"));
                    m.put("message", rs.getString("message"));
                    m.put("source", rs.getString("source"));
                    m.put("expiresAt", rs.getTimestamp("expires_at").toInstant().toString());
                    m.put("claimedByMe", rs.getBoolean("mine"));
                    m.put("myCoins", rs.getObject("my_coins") == null ? null : rs.getLong("my_coins"));
                    m.put("top", top(id, 5));
                    return m;
                }, userId, userId, parivarId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("packets", rows);
        out.put("canSend", canSend(parivarId, userId));
        out.put("funds", l(core.activeParivar(parivarId).get("funds")));
        out.put("members", l(jdbc.queryForObject("select count(*) from parivar_members where parivar_id = ?", Long.class, parivarId)));
        // the red packets the Parivar has bought and not sent yet
        out.put("owned", jdbc.query("select i.ref, i.qty, t.name, t.coins, t.pieces from parivar_inventory i join parivar_redpacket_types t on t.id = i.ref where i.parivar_id = ? and i.kind = 'REDPACKET' and i.qty > 0 order by t.sort_order, t.id",
                (rs, i2) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("typeId", String.valueOf(rs.getLong("ref")));
                    m.put("qty", rs.getLong("qty"));
                    m.put("name", rs.getString("name"));
                    m.put("coins", rs.getLong("coins"));
                    m.put("pieces", rs.getInt("pieces"));
                    return m;
                }, parivarId));
        return out;
    }

    /** The biggest pieces of a packet, for the list under it in the chat. */
    private List<Map<String, Object>> top(long packetId, int n) {
        return jdbc.query("select u.full_name, c.coins from parivar_redpacket_claims c join users u on u.id = c.user_id where c.packet_id = ? order by c.coins desc, c.created_at limit " + n,
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", rs.getString("full_name"));
                    m.put("coins", rs.getLong("coins"));
                    return m;
                }, packetId);
    }

    /** The Mukhiya, and whoever the admin allowed in the Parivar settings (SEND_REDPACKET), can send. */
    boolean canSend(Long parivarId, Long userId) {
        return core.can(parivarId, userId, "SEND_REDPACKET");
    }

    /** Everyone in the Parivar hears about it: a line in the family chat, a notice in the app and a phone notification. */
    private void announce(Long parivarId, long packetId, String from, long total, int pieces, String message, Long exceptUser) {
        String text = "::redpacket::" + packetId + "|" + from.replace("|", " ") + "|" + total + "|" + pieces + "|" + (message == null ? "" : message.replace("|", " "));
        core.system(parivarId, text);
        List<Map<String, Object>> members = jdbc.queryForList("select u.id, u.email from parivar_members m join users u on u.id = m.user_id where m.parivar_id = ?", parivarId);
        Runnable go = () -> {
            for (Map<String, Object> mm : members) {
                String email = (String) mm.get("email");
                if (email == null) continue;
                try {
                    Map<String, Object> note = new LinkedHashMap<>();
                    note.put("type", "PARIVAR_REDPACKET");
                    note.put("parivarId", String.valueOf(parivarId));
                    note.put("packetId", String.valueOf(packetId));
                    note.put("fromName", from);
                    note.put("coins", total);
                    note.put("pieces", pieces);
                    messaging.convertAndSendToUser(email, "/queue/notify", note);
                    push.toEmail(email, "🧧 Parivar me red packet!", from + " ne " + String.format("%,d", total) + " coins ka red packet bheja. Family chat me jaakar kholo!", Map.of("type", "PARIVAR_REDPACKET", "parivarId", String.valueOf(parivarId)));
                } catch (Exception ignored) {
                    // one phone failing must not stop the rest
                }
            }
        };
        // After commit, off this request's DB connection (see common.tx.AfterCommit)
        com.panchayat.backend.common.tx.AfterCommit.run(go);
    }

    @Transactional
    public Map<String, Object> send(Long userId, Long parivarId, Long typeId, boolean equal, String message, String key) {
        if (key == null || key.isBlank() || key.length() > 60) throw ParivarService.bad("A request key is required.");
        core.activeParivar(parivarId);
        if (!canSend(parivarId, userId)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can't send a red packet in this Parivar. Ask the Mukhiya.");
        if (typeId == null) throw ParivarService.bad("Choose a red packet from your store.");
        Map<String, Object> t = jdbc.queryForList("select * from parivar_redpacket_types where id = ?", typeId).stream().findFirst().orElse(null);
        if (t == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That red packet isn't available.");
        String ref = "pvrp:" + parivarId + ":" + userId + ":" + key.strip();
        if (l(jdbc.queryForObject("select count(*) from parivar_redpackets where send_key = ?", Long.class, ref)) > 0) return Map.of("ok", true);
        // it must have been bought in the shop first
        if (!economy.takeInventory(parivarId, "REDPACKET", typeId, 1)) throw conflict("Pehle Parivar shop se red packet kharido.");
        long members = l(jdbc.queryForObject("select count(*) from parivar_members where parivar_id = ?", Long.class, parivarId));
        long coins = l(t.get("coins"));
        int pieces = (int) Math.max(1, Math.min(((Number) t.get("pieces")).intValue(), Math.max(1, members)));
        pieces = (int) Math.min(pieces, coins);
        String msg = message == null || message.isBlank() ? null : message.strip().substring(0, Math.min(80, message.strip().length()));
        Long id = jdbc.queryForObject("insert into parivar_redpackets (parivar_id, sender_id, source, total, pieces, equal, message, send_key, expires_at, type_id) values (?, ?, 'SHOP', ?, ?, ?, ?, ?, ?, ?) returning id",
                Long.class, parivarId, userId, coins, pieces, equal, msg, ref, Timestamp.from(Instant.now().plus(LIFETIME)), typeId);
        announce(parivarId, id, nameOf(userId), coins, pieces, msg, userId);
        return Map.of("ok", true, "packetId", String.valueOf(id));
    }

    /** The admin sends a red packet to a Parivar from the admin panel; the coins are the platform's (nothing is taken from anyone). */
    @Transactional
    public Map<String, Object> adminSend(Long parivarId, long coins, int pieces, boolean equal, String message) {
        Map<String, Object> p = core.activeParivar(parivarId);
        long members = l(jdbc.queryForObject("select count(*) from parivar_members where parivar_id = ?", Long.class, parivarId));
        if (coins < 1 || coins > MAX_COINS) throw ParivarService.bad("Coins must be between 1 and " + MAX_COINS + ".");
        pieces = (int) Math.min(pieces, Math.min(Math.max(1, members), Math.max(1, coins)));
        if (pieces < 1 || pieces > Math.min(MAX_PIECES, Math.max(1, members)) || coins < pieces) throw ParivarService.bad("Pieces must be 1 to " + Math.min(MAX_PIECES, Math.max(1, members)) + ", at least 1 coin each.");
        String msg = message == null || message.isBlank() ? null : message.strip().substring(0, Math.min(80, message.strip().length()));
        Long id = jdbc.queryForObject("insert into parivar_redpackets (parivar_id, sender_id, source, total, pieces, equal, message, expires_at) values (?, ?, 'ADMIN', ?, ?, ?, ?, ?) returning id",
                Long.class, parivarId, ((Number) p.get("leader_id")).longValue(), coins, pieces, equal, msg, Timestamp.from(Instant.now().plus(LIFETIME)));
        announce(parivarId, id, "Panchayat", coins, pieces, msg, null);
        return Map.of("ok", true, "packetId", String.valueOf(id));
    }

    @Transactional
    public Map<String, Object> claim(Long userId, Long parivarId, Long packetId) {
        member(parivarId, userId);
        Map<String, Object> r = jdbc.queryForList("select * from parivar_redpackets where id = ? and parivar_id = ? for update", packetId, parivarId).stream().findFirst().orElse(null);
        if (r == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That red packet isn't there.");
        if (!"OPEN".equals(r.get("status")) || ((Timestamp) r.get("expires_at")).toInstant().isBefore(Instant.now())) throw conflict("This red packet has closed.");
        int pieces = ((Number) r.get("pieces")).intValue();
        int claimed = ((Number) r.get("claimed")).intValue();
        if (claimed >= pieces) throw conflict("All the pieces are taken.");
        if (l(jdbc.queryForObject("select count(*) from parivar_redpacket_claims where packet_id = ? and user_id = ?", Long.class, packetId, userId)) > 0) throw conflict("You have already opened this red packet.");
        long total = l(r.get("total"));
        long paid = l(r.get("paid"));
        long left = total - paid;
        int piecesLeft = pieces - claimed;
        long amount;
        if (piecesLeft == 1) amount = left;
        else if (Boolean.TRUE.equals(r.get("equal"))) amount = Math.max(1, total / pieces);
        else {
            // a random share: at least 1, at most about twice the average of what is left, and always leave 1 coin for each piece still to come
            long max = Math.min(left - (piecesLeft - 1), Math.max(1, left * 2 / piecesLeft));
            amount = 1 + ThreadLocalRandom.current().nextLong(Math.max(1, max));
        }
        amount = Math.max(1, Math.min(amount, left - (piecesLeft - 1)));
        jdbc.update("insert into parivar_redpacket_claims (packet_id, user_id, coins) values (?, ?, ?)", packetId, userId, amount);
        boolean done = claimed + 1 >= pieces;
        jdbc.update("update parivar_redpackets set claimed = claimed + 1, paid = paid + ?, status = ? where id = ?", amount, done ? "DONE" : "OPEN", packetId);
        wallet.credit(userId, amount, "PARIVAR_REDPACKET", "parivar:" + parivarId, "pvrp-claim:" + packetId + ":" + userId, "Parivar red packet");
        long a = amount;
        walletPush.pushBalance(userId, "PARIVAR_REDPACKET", a);
        return Map.of("amount", amount, "done", done);
    }

    /** Packets whose time is up: what was not opened goes back to the Mukhiya's wallet or the treasury. */
    @Scheduled(fixedDelay = 300_000, initialDelay = 90_000)
    public void expireDue() {
        try {
            List<Long> due = jdbc.queryForList("select id from parivar_redpackets where status = 'OPEN' and expires_at <= now() limit 50", Long.class);
            for (Long id : due) {
                try {
                    refund(id);
                } catch (Exception ignored) {
                    // try again next time
                }
            }
        } catch (Exception ignored) {
            // the tables may not exist yet
        }
    }

    @Transactional
    void refund(Long id) {
        Map<String, Object> r = jdbc.queryForList("select * from parivar_redpackets where id = ? and status = 'OPEN' for update", id).stream().findFirst().orElse(null);
        if (r == null) return;
        long back = l(r.get("total")) - l(r.get("paid"));
        jdbc.update("update parivar_redpackets set status = 'EXPIRED' where id = ?", id);
        if (back <= 0) return;
        long parivarId = l(r.get("parivar_id"));
        long sender = l(r.get("sender_id"));
        if ("ADMIN".equals(r.get("source"))) {
            return; // the platform's coins just stay unspent
        }
        if ("FUNDS".equals(r.get("source"))) core.changeFunds(parivarId, sender, back, "REDPACKETBACK", "pvrp-back:" + id);
        else {
            wallet.credit(sender, back, "PARIVAR_REDPACKET", "parivar:" + parivarId, "pvrp-back:" + id, "Parivar red packet: unopened coins back");
            walletPush.pushBalance(sender, "PARIVAR_REDPACKET", back);
        }
        core.system(parivarId, "⏳ Ek red packet band ho gaya. Bache hue " + String.format("%,d", back) + " coins wapas chale gaye.");
    }

    /* ---------------- ban and unban ---------------- */

    public List<Map<String, Object>> bans(Long userId, Long parivarId) {
        core.activeParivar(parivarId);
        if (core.roleOf(parivarId, userId) == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only members can see this.");
        core.require(parivarId, userId, "REMOVE_MEMBERS");
        return jdbc.query("select b.user_id, b.reason, b.created_at, u.full_name, u.avatar_url, u.public_id from parivar_bans b join users u on u.id = b.user_id where b.parivar_id = ? order by b.created_at desc",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("userId", String.valueOf(rs.getLong("user_id")));
                    m.put("name", rs.getString("full_name"));
                    m.put("avatarUrl", com.panchayat.backend.admin.media.MediaUrls.link(rs.getString("avatar_url")));
                    m.put("publicId", rs.getLong("public_id"));
                    m.put("reason", rs.getString("reason"));
                    m.put("at", rs.getTimestamp("created_at").toInstant().toString());
                    return m;
                }, parivarId);
    }

    @Transactional
    public Map<String, Object> ban(Long actor, Long parivarId, Long target, String reason) {
        core.activeParivar(parivarId);
        core.require(parivarId, actor, "REMOVE_MEMBERS");
        if (actor.equals(target)) throw ParivarService.bad("You can't ban yourself.");
        String tr = core.roleOf(parivarId, target);
        if (tr != null && ParivarService.rank(core.roleOf(parivarId, actor)) <= ParivarService.rank(tr)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only ban people below your role.");
        String why = reason == null || reason.isBlank() ? null : reason.strip().substring(0, Math.min(120, reason.strip().length()));
        jdbc.update("insert into parivar_bans (parivar_id, user_id, banned_by, reason) values (?, ?, ?, ?) on conflict (parivar_id, user_id) do update set banned_by = excluded.banned_by, reason = excluded.reason, created_at = now()", parivarId, target, actor, why);
        jdbc.update("update parivar_applications set status = 'REJECTED', decided_at = now() where parivar_id = ? and user_id = ? and status = 'PENDING'", parivarId, target);
        if (tr != null) {
            core.removeMember(actor, parivarId, target);
        }
        core.system(parivarId, "🚫 " + nameOf(target) + " ko Parivar se ban kiya gaya.");
        return Map.of("ok", true);
    }

    @Transactional
    public Map<String, Object> unban(Long actor, Long parivarId, Long target) {
        core.activeParivar(parivarId);
        core.require(parivarId, actor, "REMOVE_MEMBERS");
        int n = jdbc.update("delete from parivar_bans where parivar_id = ? and user_id = ?", parivarId, target);
        if (n > 0) core.system(parivarId, "✅ " + nameOf(target) + " ka ban hata diya gaya. Wo dobara jud sakta hai.");
        return Map.of("ok", true);
    }

    private String nameOf(Long userId) {
        String n = jdbc.query("select full_name from users where id = ?", rs -> rs.next() ? rs.getString(1) : null, userId);
        return n == null || n.isBlank() ? "Ek member" : n.strip();
    }

    /** Used by join and apply: a banned person can't come back until unbanned. */
    public boolean isBanned(Long parivarId, Long userId) {
        return l(jdbc.queryForObject("select count(*) from parivar_bans where parivar_id = ? and user_id = ?", Long.class, parivarId, userId)) > 0;
    }

    @SuppressWarnings("unused")
    private List<Object> unused() {
        return new ArrayList<>();
    }
}
