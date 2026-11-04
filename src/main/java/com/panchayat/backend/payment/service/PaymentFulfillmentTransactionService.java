package com.panchayat.backend.payment.service;

import com.panchayat.backend.auth.entity.User;
import com.panchayat.backend.auth.repository.UserRepository;
import com.panchayat.backend.payment.entity.PaymentTransaction;
import com.panchayat.backend.payment.entity.PaymentTransaction.Purpose;
import com.panchayat.backend.payment.entity.PaymentTransaction.Status;
import com.panchayat.backend.payment.repository.PaymentTransactionRepository;
import com.panchayat.backend.wallet.entity.CoinPack;
import com.panchayat.backend.wallet.entity.RedPacketProduct;
import com.panchayat.backend.wallet.entity.UserRedPacketInventory;
import com.panchayat.backend.wallet.repository.CoinPackRepository;
import com.panchayat.backend.wallet.repository.RedPacketProductRepository;
import com.panchayat.backend.wallet.repository.UserRedPacketInventoryRepository;
import com.panchayat.backend.wallet.service.CoinWalletService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

@Service
public class PaymentFulfillmentTransactionService {

    private final PaymentTransactionRepository paymentRepository;
    private final UserRepository userRepository;
    private final CoinPackRepository coinPackRepository;
    private final RedPacketProductRepository redPacketProductRepository;
    private final UserRedPacketInventoryRepository inventoryRepository;
    private final CoinWalletService coinWalletService;

    @org.springframework.beans.factory.annotation.Autowired
    private com.panchayat.backend.progress.service.ExpService exp;

    public PaymentFulfillmentTransactionService(
            PaymentTransactionRepository paymentRepository,
            UserRepository userRepository,
            CoinPackRepository coinPackRepository,
            RedPacketProductRepository redPacketProductRepository,
            UserRedPacketInventoryRepository inventoryRepository,
            CoinWalletService coinWalletService) {

        this.paymentRepository = paymentRepository;
        this.userRepository = userRepository;
        this.coinPackRepository = coinPackRepository;
        this.redPacketProductRepository = redPacketProductRepository;
        this.inventoryRepository = inventoryRepository;
        this.coinWalletService = coinWalletService;
    }

    @Transactional
    public void completePayment(
            String txnId,
            BigDecimal verifiedAmount,
            String paymentId,
            String paymentMode) {

        PaymentTransaction payment =
                paymentRepository.findByTxnIdForUpdate(txnId)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Payment transaction not found"));

        // Duplicate callback: do not grant the reward again.
        if (payment.getStatus() == Status.SUCCESS) {
            return;
        }

        if (payment.getStatus() != Status.PENDING) {
            throw new IllegalStateException(
                    "Payment is not pending");
        }

        if (verifiedAmount == null
                || payment.getAmount() == null
                || payment.getAmount().compareTo(verifiedAmount) != 0) {
            throw new IllegalStateException(
                    "Verified amount does not match");
        }

        Long userId = payment.getUserId();

        if (userId == null || userId <= 0) {
            throw new IllegalStateException(
                    "Invalid payment user");
        }

        User user = userRepository.findByIdForWalletUpdate(userId)
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Payment user not found"));

        if (payment.getPurpose() == Purpose.COIN_PACK) {

            Long coins = payment.getPurchasedCoins();
            Long bonusCoins = payment.getPurchasedBonusCoins();

            if (coins == null || coins <= 0
                    || bonusCoins == null || bonusCoins < 0) {
                throw new IllegalStateException(
                        "Invalid purchased coin snapshot");
            }

            long totalCoins;

            try {
                totalCoins = Math.addExact(coins, bonusCoins);
            } catch (ArithmeticException exception) {
                throw new IllegalStateException(
                        "Coin total exceeds supported limit",
                        exception);
            }

            CoinPack pack = coinPackRepository
                    .findById(payment.getProductId())
                    .orElse(null);

            String description = pack != null
                    ? "PayU coin purchase: " + pack.getName()
                    : "PayU coin purchase: " + txnId;

            coinWalletService.credit(
                    userId,
                    totalCoins,
                    "PAYMENT_PURCHASE",
                    txnId,
                    "PAYMENT:" + txnId,
                    description
            );
            // Coin packs give Active Level EXP (big packs a lot at once)
            // After commit: this transaction holds the user's row lock, and EXP takes it in its own transaction
            final long expCoins = totalCoins;
            final Long expUser = userId;
            Runnable giveExp = () -> exp.add(expUser, "PURCHASE", com.panchayat.backend.progress.service.ExpService.purchaseExp(expCoins));
            if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                        new org.springframework.transaction.support.TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                giveExp.run();
                            }
                        });
            } else {
                giveExp.run();
            }

        } else if (payment.getPurpose() == Purpose.RED_PACKET) {

            RedPacketProduct product = redPacketProductRepository
                    .findById(payment.getProductId())
                    .orElseThrow(() ->
                            new IllegalStateException(
                                    "Purchased Red Packet product not found"));

            UserRedPacketInventory inventory = inventoryRepository
                    .findByUser_IdAndRedPacketProduct_Id(
                            userId,
                            product.getId())
                    .orElseGet(() -> {
                        UserRedPacketInventory created =
                                new UserRedPacketInventory();
                        created.setUser(user);
                        created.setRedPacketProduct(product);
                        created.setQuantity(0);
                        return created;
                    });

            long quantity;

            try {
                quantity = Math.addExact(
                        inventory.getQuantity(), 1L);
            } catch (ArithmeticException exception) {
                throw new IllegalStateException(
                        "Inventory quantity limit exceeded",
                        exception);
            }

            inventory.setQuantity(quantity);
            inventoryRepository.saveAndFlush(inventory);

        } else {
            throw new IllegalStateException(
                    "Unsupported payment purpose: "
                            + payment.getPurpose());
        }

        payment.setStatus(Status.SUCCESS);
        payment.setPayuPaymentId(paymentId);
        payment.setPaymentMode(paymentMode);
        payment.setVerifiedAt(Instant.now());

        paymentRepository.save(payment);
    }
}