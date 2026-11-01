package com.panchayat.backend.payment.controller;

import com.panchayat.backend.payment.service.PaymentFulfillmentService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/payments")
public class PayUCallbackController {

    private final PaymentFulfillmentService fulfillmentService;

    public PayUCallbackController(
            PaymentFulfillmentService fulfillmentService) {
        this.fulfillmentService = fulfillmentService;
    }

    @RequestMapping(
            value = "/callback",
            method = {RequestMethod.GET, RequestMethod.POST}
    )
    public ResponseEntity<Map<String, String>> handleCallback(
            HttpServletRequest request,
            @RequestParam Map<String, String> parameters) {

        String txnId = parameters.get("txnId");

        if (txnId == null || txnId.isBlank()) {
            txnId = parameters.get("txnid");
        }

        if (txnId == null || txnId.isBlank()) {
            return ResponseEntity.badRequest().body(
                    Map.of("status", "ERROR",
                            "message", "Transaction ID is missing")
            );
        }

        try {
            fulfillmentService.fulfillVerifiedPayment(txnId);

            return ResponseEntity.ok(
                    Map.of("status", "RECEIVED",
                            "message", "Payment verification processed")
            );

        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    Map.of("status", "ERROR",
                            "message", "Invalid payment callback")
            );

        } catch (Exception exception) {
            // Do not reveal internal errors or payment details.
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    Map.of("status", "PENDING",
                            "message", "Payment verification could not be completed")
            );
        }
    }
}