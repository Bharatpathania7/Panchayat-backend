package com.panchayat.backend.payment.service;

/**
 * PayU's verification API answered, and it says the payment did not succeed
 * (failed, cancelled, or still in progress on PayU's side).
 *
 * Extends IllegalStateException so existing handlers keep working.
 */
public class PaymentNotSuccessfulException extends IllegalStateException {

    public PaymentNotSuccessfulException(String message) {
        super(message);
    }
}
