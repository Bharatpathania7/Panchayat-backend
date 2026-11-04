package com.panchayat.backend.payment.service;

/**
 * PayU's verification API answered, and it says the payment did not succeed
 * (failed, cancelled, or still in progress on PayU's side).
 *
 * Extends IllegalStateException so existing handlers keep working.
 */
public class PaymentNotSuccessfulException extends IllegalStateException {

    private final String payuStatus;

    public PaymentNotSuccessfulException(String message) {
        this(message, null);
    }

    public PaymentNotSuccessfulException(String message, String payuStatus) {
        super(message);
        this.payuStatus = payuStatus;
    }

    /** PayU's status word (failure, pending, ...), lower case, or null. */
    public String payuStatus() {
        return payuStatus == null ? null : payuStatus.toLowerCase(java.util.Locale.ROOT);
    }

    /** A final failure (not "still in progress"). */
    public boolean finalFailure() {
        String s = payuStatus();
        return s != null && (s.equals("failure") || s.equals("failed") || s.equals("dropped") || s.equals("bounced") || s.equals("usercancelled") || s.equals("cancelled"));
    }
}
