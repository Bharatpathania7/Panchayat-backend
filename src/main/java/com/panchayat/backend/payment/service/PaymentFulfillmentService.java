
        package com.panchayat.backend.payment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.panchayat.backend.payment.entity.PaymentTransaction;
import com.panchayat.backend.payment.entity.PaymentTransaction.Status;
import com.panchayat.backend.payment.repository.PaymentTransactionRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class PaymentFulfillmentService {

    private static final Logger log =
            LoggerFactory.getLogger(PaymentFulfillmentService.class);

    private final PaymentTransactionRepository paymentRepository;
    private final PayUVerificationService verificationService;
    private final PaymentFulfillmentTransactionService transactionService;
    private final com.panchayat.backend.wallet.service.WalletPushService walletPush;

    public PaymentFulfillmentService(
            PaymentTransactionRepository paymentRepository,
            PayUVerificationService verificationService,
            PaymentFulfillmentTransactionService transactionService,
            com.panchayat.backend.wallet.service.WalletPushService walletPush
    ) {
        this.paymentRepository = paymentRepository;
        this.verificationService = verificationService;
        this.transactionService = transactionService;
        this.walletPush = walletPush;
    }

    public void fulfillVerifiedPayment(String txnId) {

        if (txnId == null || txnId.isBlank()) {
            throw new IllegalArgumentException(
                    "Transaction ID is required"
            );
        }

        PaymentTransaction payment = paymentRepository.findByTxnId(txnId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Payment transaction not found"
                        )
                );

        if (payment.getStatus() == Status.SUCCESS) {
            log.info(
                    "Duplicate callback ignored; payment already successful. txnId={}",
                    txnId
            );
            return;
        }

        if (payment.getStatus() != Status.PENDING) {
            throw new IllegalStateException(
                    "Only pending payments can be fulfilled"
            );
        }

        BigDecimal expectedAmount = payment.getAmount();

        if (expectedAmount == null || expectedAmount.signum() <= 0) {
            throw new IllegalStateException(
                    "Stored payment amount is invalid"
            );
        }

        // Verify payment directly with PayU; never trust callback parameters.
        JsonNode result = verificationService.verifySuccessfulPayment(
                txnId,
                expectedAmount
        );

        if (result == null || !result.isObject()) {
            throw new IllegalStateException(
                    "PayU verification returned an invalid result"
            );
        }

        String verifiedTxnId = firstNonBlank(
                result.path("txnId").asText(null),
                result.path("txnid").asText(null)
        );

        if (verifiedTxnId == null || !txnId.equals(verifiedTxnId)) {
            throw new IllegalStateException(
                    "Verified transaction ID is missing or does not match"
            );
        }

        /*
         * Fail closed if the verification response does not contain
         * a recognized amount field. Confirm the exact field names
         * against the actual PayU verification response.
         */
        JsonNode verifiedAmountNode = result.hasNonNull("originalAmount")
                ? result.get("originalAmount")
                : result.get("amount");

        if (verifiedAmountNode == null
                || verifiedAmountNode.isNull()
                || !verifiedAmountNode.isValueNode()) {
            throw new IllegalStateException(
                    "PayU verification response does not contain a valid amount"
            );
        }

        BigDecimal verifiedAmount;

        try {
            verifiedAmount = new BigDecimal(
                    verifiedAmountNode.asText()
            );
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(
                    "PayU verification returned an invalid amount",
                    exception
            );
        }

        if (expectedAmount.compareTo(verifiedAmount) != 0) {
            throw new IllegalStateException(
                    "Verified payment amount does not match the stored amount"
            );
        }

        String paymentId = firstNonBlank(
                result.path("mihpayId").asText(null),
                result.path("mihpayid").asText(null)
        );

        String paymentMode = result.path("mode").asText(null);

        /*
         * This method must atomically transition PENDING -> SUCCESS
         * and credit the wallet exactly once, protected by database
         * transaction/locking or equivalent concurrency controls.
         */
        transactionService.completePayment(
                txnId,
                expectedAmount,
                paymentId,
                paymentMode
        );

        log.info(
                "Verified payment fulfillment completed. txnId={}",
                txnId
        );

        // Show the new balance at once on every device the buyer is signed in on
        walletPush.pushBalance(payment.getUserId(), "PURCHASE", 0);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
