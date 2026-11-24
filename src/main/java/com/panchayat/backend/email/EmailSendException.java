package com.panchayat.backend.email;

/**
 * A provider couldn't send an email. The outcome decides whether another provider may try:
 * NOT_SENT  = the provider certainly did not accept the message (bad config, auth failed, couldn't connect, rejected
 *             before acceptance, throttled): safe to try the fallback provider.
 * UNKNOWN   = the provider may already have accepted it (timeout while waiting for the answer, server error after the
 *             request went out): never sent again elsewhere, so the person can't get the same email twice.
 * The message holds the provider and a short reason only: never credentials, never the email body.
 */
public class EmailSendException extends RuntimeException {

    public enum Outcome { NOT_SENT, UNKNOWN }

    private final EmailProviderType provider;
    private final Outcome outcome;
    private final String reason;

    public EmailSendException(EmailProviderType provider, Outcome outcome, String reason, Throwable cause) {
        super(provider + " email failed (" + outcome + "): " + reason, cause);
        this.provider = provider;
        this.outcome = outcome;
        this.reason = reason;
    }

    public EmailProviderType provider() {
        return provider;
    }

    public Outcome outcome() {
        return outcome;
    }

    /** Short provider-specific reason, safe to log and to show to an admin. */
    public String reason() {
        return reason;
    }

    public boolean safeToRetryElsewhere() {
        return outcome == Outcome.NOT_SENT;
    }
}
