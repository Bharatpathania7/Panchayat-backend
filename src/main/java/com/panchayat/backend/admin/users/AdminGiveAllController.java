package com.panchayat.backend.admin.users;

import com.panchayat.backend.sarpanch.SarpanchService;
import com.panchayat.backend.wallet.service.CoinWalletService;
import com.panchayat.backend.wallet.service.WalletPushService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Gifts to everyone in one click (Super Admin): coins, or a red packet. Goes to
 * everyone who isn't banned, in the background; each person gets a message from
 * Sarpanch. A second gift can't start while one is still being sent, and each
 * person is paid once per gift even if it is retried.
 */
@RestController
@RequestMapping("/api/v1/admin/give-all")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminGiveAllController {

    private static final Logger log = LoggerFactory.getLogger(AdminGiveAllController.class);
    private static final long MAX_COINS = 1_000_000;
    private static final long MAX_PACKETS = 100;
    private static final String EVERYONE = "from users u where not (u.ban_permanent or coalesce(u.banned_until > now(), false)) and u.role = 'USER'";

    private final JdbcTemplate jdbc;
    private final CoinWalletService wallet;
    private final WalletPushService walletPush;
    private final SarpanchService sarpanch;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "give-all");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean();

    public AdminGiveAllController(JdbcTemplate jdbc, CoinWalletService wallet, WalletPushService walletPush, SarpanchService sarpanch) {
        this.jdbc = jdbc;
        this.wallet = wallet;
        this.walletPush = walletPush;
        this.sarpanch = sarpanch;
    }

    public record CoinsRequest(Long coins, String reason) {
    }

    public record PacketRequest(Long productId, Long quantity, String reason) {
    }

    @PostMapping("/coins")
    public Map<String, Object> coins(@RequestBody CoinsRequest b, Authentication auth) {
        if (b.coins() == null || b.coins() < 1 || b.coins() > MAX_COINS) throw bad("Coins must be between 1 and " + MAX_COINS + ".");
        String reason = clean(b.reason());
        long coins = b.coins();
        long id = start("COINS", coins, null, null, reason, auth);
        List<Long> ids = recipients();
        worker.execute(() -> {
            int done = 0;
            try {
                for (Long uid : ids) {
                    try {
                        wallet.credit(uid, coins, "ADMIN_GIFT_ALL", "gift-all:" + id, "gift-all:" + id + ":" + uid, reason == null ? "Gift from Panchayat" : reason);
                        walletPush.pushBalance(uid, "ADMIN_GIFT_ALL", coins);
                        notice(uid, "A gift from Panchayat", "🎁 You received " + coins + " coins" + (reason == null ? "." : ": " + reason), "gift-all-msg:" + id + ":" + uid);
                        done++;
                    } catch (Exception e) {
                        log.warn("Gift {} to user {} failed: {}", id, uid, e.getMessage());
                    }
                }
            } finally {
                finish(id, done);
            }
        });
        return started(id, ids.size());
    }

    @PostMapping("/red-packets")
    public Map<String, Object> redPackets(@RequestBody PacketRequest b, Authentication auth) {
        if (b.productId() == null) throw bad("Pick a red packet.");
        long qty = b.quantity() == null ? 1 : b.quantity();
        if (qty < 1 || qty > MAX_PACKETS) throw bad("Quantity must be between 1 and " + MAX_PACKETS + ".");
        String name = jdbc.query("select name from red_packet_products where id = ?", rs -> rs.next() ? rs.getString(1) : null, b.productId());
        if (name == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Red packet not found");
        String reason = clean(b.reason());
        long id = start("RED_PACKET", null, b.productId(), qty, reason, auth);
        List<Long> ids = recipients();
        worker.execute(() -> {
            int done = 0;
            try {
                for (Long uid : ids) {
                    try {
                        jdbc.update("insert into user_red_packet_inventory (user_id, red_packet_product_id, quantity) values (?, ?, ?)"
                                + " on conflict (user_id, red_packet_product_id) do update set quantity = user_red_packet_inventory.quantity + excluded.quantity, updated_at = now()",
                                uid, b.productId(), qty);
                        notice(uid, "A gift from Panchayat", "🧧 You received " + qty + " × " + name + " red packet" + (qty > 1 ? "s" : "") + " in your Bag" + (reason == null ? "." : ": " + reason), "gift-all-msg:" + id + ":" + uid);
                        done++;
                    } catch (Exception e) {
                        log.warn("Red packet gift {} to user {} failed: {}", id, uid, e.getMessage());
                    }
                }
            } finally {
                finish(id, done);
            }
        });
        return started(id, ids.size());
    }

    @GetMapping("/history")
    public List<Map<String, Object>> history() {
        return jdbc.queryForList("select id, kind, coins, product_id as \"productId\", quantity, reason, admin_name as \"adminName\", recipients, status, created_at as \"createdAt\", finished_at as \"finishedAt\""
                + " from admin_gift_all order by id desc limit 20");
    }

    /** How many people a gift would reach right now. */
    @GetMapping("/audience")
    public Map<String, Object> audience() {
        Long n = jdbc.queryForObject("select count(*) " + EVERYONE, Long.class);
        return Map.of("recipients", n == null ? 0 : n);
    }

    private long start(String kind, Long coins, Long productId, Long qty, String reason, Authentication auth) {
        if (!running.compareAndSet(false, true)) throw new ResponseStatusException(HttpStatus.CONFLICT, "A gift is still being sent. Wait for it to finish.");
        try {
            Long id = jdbc.queryForObject("insert into admin_gift_all (kind, coins, product_id, quantity, reason, admin_name) values (?, ?, ?, ?, ?, ?) returning id",
                    Long.class, kind, coins, productId, qty, reason, auth == null ? null : auth.getName());
            return id == null ? 0 : id;
        } catch (RuntimeException e) {
            running.set(false);
            throw e;
        }
    }

    private void finish(long id, int done) {
        try {
            jdbc.update("update admin_gift_all set recipients = ?, status = 'DONE', finished_at = now() where id = ?", done, id);
        } finally {
            running.set(false);
        }
    }

    private List<Long> recipients() {
        List<Long> ids = jdbc.queryForList("select u.id " + EVERYONE + " order by u.id", Long.class);
        if (ids.isEmpty()) {
            running.set(false);
            throw bad("There is nobody to send to.");
        }
        return ids;
    }

    private void notice(Long uid, String title, String text, String eventKey) {
        try {
            sarpanch.send(uid, new SarpanchService.Message(title, text, null, null, null, "REWARD"), eventKey);
        } catch (Exception e) {
            log.debug("Gift notice not sent to {}: {}", uid, e.getMessage());
        }
    }

    private static Map<String, Object> started(long id, int recipients) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("recipients", recipients);
        m.put("status", "RUNNING");
        return m;
    }

    private static String clean(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.strip();
        return t.substring(0, Math.min(200, t.length()));
    }

    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }
}
