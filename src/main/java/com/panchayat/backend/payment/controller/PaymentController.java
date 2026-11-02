
        package com.panchayat.backend.payment.controller;

import com.panchayat.backend.auth.entity.User;
import com.panchayat.backend.auth.repository.UserRepository;
import com.panchayat.backend.payment.dto.PaymentInitiationRequest;
import com.panchayat.backend.payment.dto.PaymentInitiationResponse;
import com.panchayat.backend.payment.service.PaymentInitiationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentInitiationService paymentInitiationService;
    private final UserRepository userRepository;

    public PaymentController(
            PaymentInitiationService paymentInitiationService,
            UserRepository userRepository
    ) {
        this.paymentInitiationService = paymentInitiationService;
        this.userRepository = userRepository;
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

        PaymentInitiationResponse response =
                paymentInitiationService.initiatePayment(user, request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }
}
