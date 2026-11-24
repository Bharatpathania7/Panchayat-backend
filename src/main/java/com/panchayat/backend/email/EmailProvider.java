package com.panchayat.backend.email;

/** One way of sending email (Brevo SMTP, Amazon SES). */
public interface EmailProvider {

    EmailProviderType type();

    /** Has the settings it needs (credentials, sender). Says nothing about whether the provider account is healthy. */
    boolean isConfigured();

    /**
     * Sends the email once (no retries inside). Returns the provider's message id, or null when it gives none.
     * On failure throws EmailSendException saying whether the message was certainly not accepted.
     */
    String send(EmailMessage message) throws EmailSendException;
}
