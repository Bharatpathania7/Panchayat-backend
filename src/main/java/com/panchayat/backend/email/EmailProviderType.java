package com.panchayat.backend.email;

import java.util.Locale;

/** The email providers the backend can use. BREVO = Brevo's SMTP relay (spring.mail.*), SES = Amazon SES API v2. */
public enum EmailProviderType {
    BREVO,
    SES;

    /** BREVO / SES (any case); null for an empty value or NONE. Anything else is a configuration mistake. */
    public static EmailProviderType parse(String value) {
        if (value == null || value.isBlank() || value.strip().equalsIgnoreCase("NONE")) return null;
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown email provider '" + value.strip() + "'. Use BREVO or SES.");
        }
    }
}
