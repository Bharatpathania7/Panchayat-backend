package com.panchayat.backend.email;

/**
 * One email, ready to send by any provider: plain text and HTML of the same content.
 * category is only for logs and reports (OTP, MAINTENANCE, TEST, ...), never shown to the reader.
 */
public record EmailMessage(String to, String subject, String text, String html, String category) {

    public EmailMessage {
        if (to == null || to.isBlank()) throw new IllegalArgumentException("Email recipient is missing");
        if (subject == null || subject.isBlank()) throw new IllegalArgumentException("Email subject is missing");
        if ((text == null || text.isBlank()) && (html == null || html.isBlank())) throw new IllegalArgumentException("Email body is missing");
        to = to.strip();
        category = category == null || category.isBlank() ? "GENERAL" : category;
    }

    /** The recipient for logs: "ab***@gmail.com" (never the full address). */
    public String maskedTo() {
        return mask(to);
    }

    static String mask(String email) {
        int at = email == null ? -1 : email.indexOf('@');
        if (at <= 0) return "***";
        String local = email.substring(0, at);
        return (local.length() <= 2 ? local.charAt(0) + "*" : local.substring(0, 2) + "***") + email.substring(at);
    }
}
