package com.panchayat.backend.payment.play;

import com.panchayat.backend.auth.entity.User;
import com.panchayat.backend.auth.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Google Play Billing for the Android app.
 *   GET  /api/v1/payments/google-play/products   which Play product is which coin pack / VIP plan / red packet
 *   POST /api/v1/payments/google-play/verify     { purpose, productId, playProductId, purchaseToken }
 * Admin: GET /api/v1/admin/play-products, PUT /api/v1/admin/play-products { purpose, productId, playProductId }
 */
@RestController
public class GooglePlayController {

    private final GooglePlayService play;
    private final UserRepository users;
    private final JdbcTemplate jdbc;

    public GooglePlayController(GooglePlayService play, UserRepository users, JdbcTemplate jdbc) {
        this.play = play;
        this.users = users;
        this.jdbc = jdbc;
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handle(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("message", e.getReason() == null ? "That didn't work" : e.getReason()));
    }

    private Long me(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null || "anonymousUser".equals(auth.getPrincipal())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        Object p = auth.getPrincipal();
        if (p instanceof User u && u.getId() != null) return u.getId();
        return users.findByEmailIgnoreCase(auth.getName()).map(User::getId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Only user accounts can buy"));
    }

    @GetMapping("/api/v1/payments/google-play/products")
    public List<Map<String, Object>> products(Authentication auth) {
        me(auth);
        return play.products();
    }

    public record VerifyBody(String purpose, Long productId, String playProductId, String purchaseToken) {
    }

    @PostMapping("/api/v1/payments/google-play/verify")
    public Map<String, Object> verify(@RequestBody VerifyBody b, Authentication auth) {
        Long id = me(auth);
        if (b == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nothing to check.");
        return play.redeem(id, b.purpose(), b.productId(), b.playProductId(), b.purchaseToken());
    }

    /* ----------------------------- admin ----------------------------- */

    @GetMapping("/api/v1/admin/play-products")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public List<Map<String, Object>> adminList() {
        List<Map<String, Object>> out = new ArrayList<>();
        String[][] tables = {{"COIN_PACK", "coin_packs"}, {"VIP", "vip_plans"}, {"RED_PACKET", "red_packet_products"}};
        for (String[] t : tables) {
            out.addAll(jdbc.query("select x.id, x.name, x.price_in_rupees, x.active, p.play_product_id from " + t[1] + " x left join play_products p on p.purpose = ? and p.product_id = x.id order by x.id",
                    (rs, i) -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("purpose", t[0]);
                        m.put("productId", rs.getLong("id"));
                        m.put("name", rs.getString("name"));
                        m.put("priceInRupees", rs.getBigDecimal("price_in_rupees"));
                        m.put("active", rs.getBoolean("active"));
                        m.put("playProductId", rs.getString("play_product_id"));
                        return m;
                    }, t[0]));
        }
        return out;
    }

    public record MapBody(String purpose, Long productId, String playProductId) {
    }

    @PutMapping("/api/v1/admin/play-products")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public Map<String, Object> adminSave(@RequestBody MapBody b) {
        if (b == null || b.purpose() == null || b.productId() == null || !Set.of("COIN_PACK", "VIP", "RED_PACKET").contains(b.purpose())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pick a product.");
        String pid = b.playProductId() == null ? "" : b.playProductId().strip();
        if (pid.isEmpty()) {
            jdbc.update("delete from play_products where purpose = ? and product_id = ?", b.purpose(), b.productId());
            return Map.of("productId", b.productId(), "playProductId", "");
        }
        if (!pid.matches("[a-z0-9][a-z0-9._]{0,98}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A Play product ID has lower-case letters, digits, dots and underscores only (for example coins_500).");
        try {
            jdbc.update("insert into play_products (purpose, product_id, play_product_id) values (?, ?, ?) on conflict (purpose, product_id) do update set play_product_id = excluded.play_product_id, updated_at = now()", b.purpose(), b.productId(), pid);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That Play product ID is already used for another item.");
        }
        return Map.of("productId", b.productId(), "playProductId", pid);
    }
}
