package com.panchayat.backend.payment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.panchayat.backend.payment.entity.PaymentTransaction;
import com.panchayat.backend.payment.entity.PaymentTransaction.Status;
import com.panchayat.backend.payment.repository.PaymentTransactionRepository;

import org.springframework.stereotype.Service;

@Service
public class PaymentFulfillmentService {

    private final PaymentTransactionRepository paymentRepository;
    private final PayUVerificationService verificationService;
    private final PaymentFulfillmentTransactionService transactionService;

    public PaymentFulfillmentService(
            PaymentTransactionRepository paymentRepository,
            PayUVerificationService verificationService,
            PaymentFulfillmentTransactionService transactionService) {

        this.paymentRepository = paymentRepository;
        this.verificationService = verificationService;
        this.transactionService = transactionService;
    }

    public void fulfillVerifiedPayment(String txnId) {

        if (txnId == null || txnId.isBlank()) {
            throw new IllegalArgumentException(
                    "Transaction ID is required");
        }

        PaymentTransaction payment = paymentRepository.findByTxnId(txnId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Payment transaction not found"));

        if (payment.getStatus() == Status.SUCCESS) {
            return;
        }

        if (payment.getStatus() != Status.PENDING) {
            throw new IllegalStateException(
                    "Only pending payments can be fulfilled");
        }

        // Verify directly with PayU; never trust the callback alone.
        JsonNode result = verificationService.verifySuccessfulPayment(
                txnId,
                payment.getAmount()
        );

        String verifiedTxnId = result.path("txnId").asText("");

        if (!txnId.equals(verifiedTxnId)) {
            throw new IllegalStateException(
                    "Verified transaction ID does not match");
        }

        String paymentId = result.path("mihpayId").asText(null);
        String paymentMode = result.path("mode").asText(null);

        transactionService.completePayment(
                txnId,
                payment.getAmount(),
                paymentId,
                paymentMode
        );
    }
}