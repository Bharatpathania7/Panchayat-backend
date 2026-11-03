package com.panchayat.backend.shop.service;

import com.panchayat.backend.admin.shop.entity.ShopProduct;
import com.panchayat.backend.admin.shop.repository.ShopProductRepository;
import com.panchayat.backend.rewards.service.SpendRewardService;
import com.panchayat.backend.wallet.service.CoinWalletService;
import com.panchayat.backend.wallet.service.WalletPushService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Buying Shop items with coins.
 *
 * One transaction: take the price (from the server's catalog, never the app)
 * from the buyer's wallet, add the item to their Bag (user_shop_items), and
 * count the coins towards spend rewards. A retry with the same requestId
 * (a network blip) never charges twice. The new balance is pushed to the
 * buyer's /user/queue/wallet after it commits.
 */
@Service
public class ShopPurchaseService {

    private static final Pattern REQUEST_ID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");
    private static final int MAX_QUANTITY = 99;

    private final ShopProductRepository products;
    private final CoinWalletService wallet;
    private final WalletPushService walletPush;
    private final SpendRewardService rewards;
    private final JdbcTemplate jdbc;

    public ShopPurchaseService(ShopProductRepository products, CoinWalletService wallet, WalletPushService walletPush,
                               SpendRewardService rewards, JdbcTemplate jdbc) {
        this.products = products;
        this.wallet = wallet;
        this.walletPush = walletPush;
        this.rewards = rewards;
        this.jdbc = jdbc;
    }

    @Transactional
    public Map<String, Object> buy(Long userId, Long productId, Integer quantity, String requestId) {
        if (requestId == null || !REQUEST_ID.matcher(requestId).matches()) throw bad("A request id is required");
        int qty = quantity == null ? 1 : quantity;
        if (qty < 1 || qty > MAX_QUANTITY) throw bad("Buy between 1 and " + MAX_QUANTITY + " at a time");

        ShopProduct p = products.findById(productId == null ? -1L : productId)
                .filter(x -> Boolean.TRUE.equals(x.getActive()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "This item isn't on sale any more."));
        BigDecimal price = p.getPrice();
        if (price == null || price.signum() <= 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "This item doesn't have a price yet.");
        long unit;
        long coins;
        try {
            unit = price.longValueExact();
            coins = Math.multiplyExact(unit, qty);
        } catch (ArithmeticException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This item's price isn't a whole number of coins.");
        }

        String key = "shop:" + requestId;
        // The same purchase sent again: answer as before, charge nothing
        Boolean done = jdbc.queryForObject("select exists (select 1 from coin_transactions where idempotency_key = ?)", Boolean.class, key);
        if (Boolean.TRUE.equals(done)) return result(userId, p, 0, wallet.getBalance(userId));

        try {
            wallet.debit(userId, coins, "SHOP_PURCHASE", "shop:" + p.getId(), key, qty + "x " + p.getName());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Not enough coins. Recharge to buy this.");
        }
        jdbc.update("insert into user_shop_items (user_id, product_id, quantity) values (?, ?, ?)"
                        + " on conflict (user_id, product_id) do update"
                        + " set quantity = user_shop_items.quantity + excluded.quantity, last_bought_at = now()",
                userId, p.getId(), qty);
        // Shop spending counts towards random and guaranteed rewards too
        rewards.onSpend(userId, coins);

        long balance = wallet.getBalance(userId);
        afterCommit(() -> walletPush.pushBalance(userId, "SHOP_PURCHASE", -coins));
        return result(userId, p, coins, balance);
    }

    /** Everything you bought, newest first, with the product's details. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> mine(Long userId) {
        return jdbc.query(
                "select i.product_id, i.quantity, i.first_bought_at, i.last_bought_at,"
                        + " p.name, p.description, p.price, p.rarity, p.asset_url, p.asset_type, p.active, c.id as category_id, c.name as category_name"
                        + " from user_shop_items i join shop_products p on p.id = i.product_id"
                        + " left join shop_categories c on c.id = p.category_id"
                        + " where i.user_id = ? and i.quantity > 0 order by i.last_bought_at desc",
                (rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("productId", rs.getLong("product_id"));
                    m.put("name", rs.getString("name"));
                    m.put("description", rs.getString("description"));
                    m.put("categoryId", rs.getObject("category_id"));
                    m.put("categoryName", rs.getString("category_name"));
                    m.put("rarity", rs.getString("rarity"));
                    m.put("assetUrl", rs.getString("asset_url"));
                    m.put("assetType", rs.getString("asset_type"));
                    m.put("price", rs.getBigDecimal("price"));
                    m.put("quantity", rs.getInt("quantity"));
                    m.put("firstBoughtAt", ts(rs.getTimestamp("first_bought_at")));
                    m.put("lastBoughtAt", ts(rs.getTimestamp("last_bought_at")));
                    return m;
                }, userId);
    }

    private Map<String, Object> result(Long userId, ShopProduct p, long spent, long balance) {
        Integer owned = jdbc.query("select quantity from user_shop_items where user_id = ? and product_id = ?",
                rs -> rs.next() ? rs.getInt(1) : 0, userId, p.getId());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productId", p.getId());
        m.put("name", p.getName());
        m.put("owned", owned == null ? 0 : owned);
        m.put("coinsSpent", spent);
        m.put("balance", balance);
        return m;
    }

    private static Object ts(Timestamp t) {
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

    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }
}
