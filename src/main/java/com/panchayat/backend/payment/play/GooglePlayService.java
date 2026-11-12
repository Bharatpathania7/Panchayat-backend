package com.panchayat.backend.payment.play;

import com.panchayat.backend.payment.entity.PaymentTransaction;
import com.panchayat.backend.payment.entity.PaymentTransaction.Purpose;
import com.panchayat.backend.payment.repository.PaymentTransactionRepository;
import com.panchayat.backend.payment.service.PaymentFulfillmentTransactionService;
import com.panchayat.backend.vip.VipService;
import com.panchayat.backend.wallet.entity.CoinPack;
import com.panchayat.backend.wallet.entity.RedPacketProduct;
import com.panchayat.backend.wallet.repository.RedPacketProductRepository;
import com.panchayat.backend.wallet.service.CoinPackService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.*;

/**
 * Coins, VIP and red packets bought in the Android app through Google Play Billing.
 * The app sends the purchase token Google gave; the server asks Google if it is really paid, credits it once (the token is
 * unique), and tells Google it was delivered. The price and what is delivered always come from our own tables.
 */
@Service
public class GooglePlayService {

    private final JdbcTemplate jdbc;
    private final PlayVerifier verifier;
    private final PaymentTransactionRepository transactions;
    private final CoinPackService coinPacks;
    private final VipService vip;
    private final RedPacketProductRepository redPackets;
    private final PaymentFulfillmentTransactionService fulfillment;
    private final TransactionTemplate tx;

    public GooglePlayService(JdbcTemplate jdbc, PlayVerifier verifier, PaymentTransactionRepository transactions, CoinPackService coinPacks, VipService vip,
                             RedPacketProductRepository redPackets, PaymentFulfillmentTransactionService fulfillment, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.verifier = verifier;
        this.transactions = transactions;
        this.coinPacks = coinPacks;
        this.vip = vip;
        this.redPackets = redPackets;
        this.fulfillment = fulfillment;
        this.tx = tx;
    }

    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }

    /** Our product ids for every Play product id the admin has set. */
    public List<Map<String, Object>> products() {
        return jdbc.queryForList("select purpose, product_id as \"productId\", play_product_id as \"playProductId\" from play_products order by purpose, product_id");
    }

    public Map<String, Object> redeem(Long userId, String purposeText, Long productId, String playProductId, String purchaseToken) {
        Purpose purpose;
        try {
            purpose = Purpose.valueOf(purposeText == null ? "" : purposeText.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw bad("Pick what was bought.");
        }
        String token = purchaseToken == null ? "" : purchaseToken.strip();
        if (productId == null || playProductId == null || playProductId.isBlank() || token.length() < 8 || token.length() > 400) throw bad("The purchase details are incomplete.");
        String pid = playProductId.strip();

        Integer mapped = jdbc.queryForObject("select count(*) from play_products where purpose = ? and product_id = ? and play_product_id = ?", Integer.class, purpose.name(), productId, pid);
        if (mapped == null || mapped == 0) throw bad("That Google Play product isn't one of ours.");

        List<Long> seen = jdbc.queryForList("select user_id from google_play_purchases where purchase_token = ?", Long.class, token);
        if (!seen.isEmpty()) {
            if (!seen.get(0).equals(userId)) throw new ResponseStatusException(HttpStatus.CONFLICT, "That purchase belongs to another account.");
            return Map.of("credited", false, "already", true);
        }

        PlayVerifier.PlayPurchase p = verifier.verify(pid, token);
        if (p.purchaseState() == 2) throw new ResponseStatusException(HttpStatus.CONFLICT, "Google is still processing this payment. It will be added when it completes.");
        if (p.purchaseState() != 0) throw bad("Google says this purchase wasn't paid.");

        Map<String, Object> out = tx.execute(status -> {
            int added = jdbc.update("insert into google_play_purchases (purchase_token, order_id, user_id, purpose, product_id, play_product_id) values (?, ?, ?, ?, ?, ?) on conflict (purchase_token) do nothing",
                    token, p.orderId(), userId, purpose.name(), productId, pid);
            if (added == 0) return Map.<String, Object>of("credited", false, "already", true);
            String txnId = "GPA" + UUID.randomUUID().toString().replace("-", "").substring(0, 25);
            PaymentTransaction t = new PaymentTransaction();
            t.setTxnId(txnId);
            t.setUserId(userId);
            t.setPurpose(purpose);
            t.setProductId(productId);
            t.setStatus(PaymentTransaction.Status.PENDING);
            BigDecimal amount;
            switch (purpose) {
                case COIN_PACK -> {
                    CoinPack pack = coinPacks.getActivePackById(productId);
                    amount = pack.getPriceInRupees();
                    t.setPurchasedCoins(pack.getCoins());
                    t.setPurchasedBonusCoins(pack.getBonusCoins());
                }
                case VIP -> {
                    VipService.Plan plan = vip.activePlan(productId);
                    amount = plan.price();
                    t.setPurchasedVipDays(plan.days());
                }
                case RED_PACKET -> {
                    RedPacketProduct product = redPackets.findByIdAndActiveTrue(productId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "That red packet isn't on sale."));
                    amount = product.getPriceInRupees();
                }
                default -> throw bad("Unsupported purchase.");
            }
            t.setAmount(amount);
            transactions.saveAndFlush(t);
            fulfillment.completePayment(txnId, amount, p.orderId(), "GOOGLE_PLAY");
            jdbc.update("update google_play_purchases set txn_id = ? where purchase_token = ?", txnId, token);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("credited", true);
            r.put("already", false);
            r.put("txnId", txnId);
            return r;
        });
        if (out != null && Boolean.TRUE.equals(out.get("credited")) && p.acknowledgementState() == 0) verifier.acknowledge(pid, token);
        return out == null ? Map.of("credited", false) : out;
    }
}
