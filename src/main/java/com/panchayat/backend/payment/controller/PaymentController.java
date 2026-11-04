package com.panchayat.backend.payment.controller;

import com.panchayat.backend.auth.entity.User;
import com.panchayat.backend.auth.repository.UserRepository;
import com.panchayat.backend.payment.dto.PaymentInitiationRequest;
import com.panchayat.backend.payment.dto.PaymentInitiationResponse;
import com.panchayat.backend.payment.entity.PaymentTransaction;
import com.panchayat.backend.payment.repository.PaymentTransactionRepository;
import com.panchayat.backend.payment.service.PaymentInitiationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentInitiationService paymentInitiationService;
    private final UserRepository userRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;

    public PaymentController(
            PaymentInitiationService paymentInitiationService,
            UserRepository userRepository,
            PaymentTransactionRepository paymentTransactionRepository
    ) {
        this.paymentInitiationService = paymentInitiationService;
        this.userRepository = userRepository;
        this.paymentTransactionRepository = paymentTransactionRepository;
    }

    @PostMapping("/initiate")
    public ResponseEntity<PaymentInitiationResponse> initiatePayment(
            @Valid @RequestBody PaymentInitiationRequest request,
            Authentication authentication
    ) {
        User user = resolveUser(authentication);

        PaymentInitiationResponse response =
                paymentInitiationService.initiatePayment(user, request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    /**
     * Status of one of the signed-in person's own payments, for the app to
     * show after returning from PayU. Another user's transaction ID answers
     * 404, exactly like an unknown one, so IDs can't be probed.
     */
    @GetMapping("/{txnId}/status")
    public ResponseEntity<Map<String, Object>> getPaymentStatus(
            @PathVariable String txnId,
            Authentication authentication
    ) {
        User user = resolveUser(authentication);

        PaymentTransaction payment = paymentTransactionRepository
                .findByTxnId(txnId)
                .filter(p -> Objects.equals(p.getUserId(), user.getId()))
                .orElse(null);

        if (payment == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    Map.of("status", "NOT_FOUND")
            );
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("txnId", payment.getTxnId());
        body.put("status", payment.getStatus().name());
        body.put("purpose", payment.getPurpose().name());
        body.put("productId", payment.getProductId());
        body.put("amount", payment.getAmount());

        if (payment.getPurchasedCoins() != null) {
            long bonus = payment.getPurchasedBonusCoins() == null
                    ? 0L
                    : payment.getPurchasedBonusCoins();
            body.put("coins", payment.getPurchasedCoins() + bonus);
        }

        return ResponseEntity.ok(body);
    }

    /** One of your own orders (the Sarpanch "Order ..." link). Another user's order answers 404. */
    @GetMapping("/{orderRef}")
    public ResponseEntity<Map<String, Object>> getOrder(@PathVariable String orderRef, Authentication authentication) {
        User user = resolveUser(authentication);
        PaymentTransaction payment = paymentTransactionRepository.findByTxnId(orderRef)
                .filter(p -> Objects.equals(p.getUserId(), user.getId()))
                .orElse(null);
        if (payment == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Order not found"));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderRef", payment.getTxnId());
        body.put("purpose", payment.getPurpose().name());
        body.put("productId", payment.getProductId());
        body.put("amountInRupees", payment.getAmount());
        Long coins = null;
        if (payment.getPurchasedCoins() != null) {
            coins = payment.getPurchasedCoins() + (payment.getPurchasedBonusCoins() == null ? 0L : payment.getPurchasedBonusCoins());
        }
        body.put("coins", coins);
        body.put("status", payment.getStatus().name());
        body.put("createdAt", payment.getCreatedAt() == null ? null : payment.getCreatedAt().toString());
        return ResponseEntity.ok(body);
    }

    private User resolveUser(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication.getPrincipal() == null
                || "anonymousUser".equals(authentication.getPrincipal())) {
            throw new AccessDeniedException(
                    "Authentication is required"
            );
        }

        Object principal = authentication.getPrincipal();

        final User user;

        if (principal instanceof User authenticatedUser) {
            user = authenticatedUser;
        } else if (principal instanceof String email) {
            user = userRepository.findByEmail(email)
                    .orElseThrow(() -> new AccessDeniedException(
                            "Authenticated user account could not be resolved"
                    ));
        } else {
            throw new AccessDeniedException(
                    "Unsupported authenticated principal"
            );
        }

        if (user.getId() == null || user.getId() <= 0) {
            throw new AccessDeniedException(
                    "Authenticated user account could not be resolved"
            );
        }

        return user;
    }
}
