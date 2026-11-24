package com.panchayat.backend.email;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

/**
 * Brevo, through its SMTP relay: exactly what the backend always used (spring.mail.* = MAIL_HOST, MAIL_PORT, MAIL_USERNAME,
 * MAIL_APP_PASSWORD; sender MAIL_FROM / MAIL_FROM_NAME). Nothing about it changed; it is now one provider among two.
 */
@Component
public class BrevoSmtpEmailProvider implements EmailProvider {

    private final JavaMailSender mailSender;
    private final String username;
    private final String fromAddress;
    private final String fromName;

    public BrevoSmtpEmailProvider(
            JavaMailSender mailSender,
            @Value("${spring.mail.username:}") String username,
            @Value("${app.mail.from:}") String from,
            @Value("${app.mail.from-name:Panchayat}") String fromName
    ) {
        this.mailSender = mailSender;
        this.username = username == null ? "" : username.strip();
        // A domain sender (MAIL_FROM) when set, else the login address. Shown as "Panchayat <no-reply@...>".
        this.fromAddress = from == null || from.isBlank() ? this.username : from.strip();
        this.fromName = fromName == null || fromName.isBlank() ? "Panchayat" : fromName.strip();
    }

    @Override
    public EmailProviderType type() {
        return EmailProviderType.BREVO;
    }

    @Override
    public boolean isConfigured() {
        return !username.isEmpty() && !fromAddress.isEmpty();
    }

    public String fromAddress() {
        return fromAddress;
    }

    @Override
    public String send(EmailMessage m) {
        if (!isConfigured()) throw new EmailSendException(type(), EmailSendException.Outcome.NOT_SENT, "SMTP login (MAIL_USERNAME) or sender isn't set", null);
        MimeMessage mime;
        try {
            mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, StandardCharsets.UTF_8.name());
            helper.setFrom(new InternetAddress(fromAddress, fromName, StandardCharsets.UTF_8.name()));
            helper.setTo(m.to());
            helper.setSubject(m.subject());
            // Plain text first, HTML second: mail apps show the last part they understand
            if (m.html() != null && !m.html().isBlank()) helper.setText(m.text() == null ? "" : m.text(), m.html());
            else helper.setText(m.text());
        } catch (MessagingException | java.io.UnsupportedEncodingException e) {
            throw new EmailSendException(type(), EmailSendException.Outcome.NOT_SENT, "couldn't build the message", e);
        }
        try {
            mailSender.send(mime);
        } catch (MailException e) {
            throw classify(e);
        }
        try {
            return mime.getMessageID();
        } catch (MessagingException e) {
            return null;
        }
    }

    /** Was the message certainly not handed to Brevo, or might it have been? */
    EmailSendException classify(MailException e) {
        if (e instanceof MailAuthenticationException) return notSent("SMTP login was refused (check MAIL_USERNAME / MAIL_APP_PASSWORD)", e);
        if (e instanceof MailPreparationException || e instanceof MailParseException) return notSent("couldn't build the message", e);
        if (e instanceof MailSendException mse) {
            for (Exception inner : mse.getMessageExceptions()) {
                EmailSendException c = fromCause(inner, e);
                if (c != null) return c;
            }
        }
        EmailSendException c = fromCause(e.getCause(), e);
        if (c != null) return c;
        // Anything else: the server may have taken the message before the error, so never resend it elsewhere
        return new EmailSendException(type(), EmailSendException.Outcome.UNKNOWN, "SMTP error after the message may have been accepted: " + e.getClass().getSimpleName(), e);
    }

    private EmailSendException fromCause(Throwable start, MailException original) {
        for (Throwable t = start; t != null; t = t.getCause()) {
            if (t instanceof AuthenticationFailedException) return notSent("SMTP login was refused (check MAIL_USERNAME / MAIL_APP_PASSWORD)", original);
            if ("MailConnectException".equals(t.getClass().getSimpleName()) || t instanceof ConnectException || t instanceof UnknownHostException || t instanceof NoRouteToHostException) {
                return notSent("couldn't connect to the SMTP server (MAIL_HOST / MAIL_PORT)", original);
            }
            // The server said no to the recipient or sender before taking the message
            if (t instanceof SendFailedException) return notSent("the SMTP server refused the message or recipient", original);
            if (t instanceof SocketTimeoutException ste) {
                String msg = ste.getMessage() == null ? "" : ste.getMessage().toLowerCase(java.util.Locale.ROOT);
                if (msg.contains("connect")) return notSent("timed out connecting to the SMTP server", original);
                return new EmailSendException(type(), EmailSendException.Outcome.UNKNOWN, "timed out waiting for the SMTP server's answer", original);
            }
        }
        return null;
    }

    private EmailSendException notSent(String reason, Throwable cause) {
        return new EmailSendException(type(), EmailSendException.Outcome.NOT_SENT, reason, cause);
    }
}
