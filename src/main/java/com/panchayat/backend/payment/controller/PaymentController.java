package com.panchayat.backend.payment.controller;

import com.panchayat.backend.auth.entity.User;
import com.panchayat.backend.payment.dto.PaymentInitiationRequest;
import com.panchayat.backend.payment.dto.PaymentInitiationResponse;
import com.panchayat.backend.payment.service.PaymentInitiationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentInitiationService paymentInitiationService;

    public PaymentController(
            PaymentInitiationService paymentInitiationService
    ) {
        this.paymentInitiationService = paymentInitiationService;
    }

    @PostMapping("/initiate")
    public ResponseEntity<PaymentInitiationResponse> initiatePayment(
            @Valid @RequestBody PaymentInitiationRequest request,
            Authentication authentication
    ) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication.getPrincipal() == null
                || "anonymousUser".equals(authentication.getPrincipal())) {
            throw new AccessDeniedException(
                    "Authentication is required to initiate payment"
            );
        }

        Object principal = authentication.getPrincipal();

        if (!(principal instanceof User user)
                || user.getId() == null
                || user.getId() <= 0) {
            throw new AccessDeniedException(
                    "Authenticated user account could not be resolved"
            );
        }

        PaymentInitiationResponse response =
                paymentInitiationService.initiatePayment(user, request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }
}