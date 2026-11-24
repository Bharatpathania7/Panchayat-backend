package com.panchayat.backend.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sends every email through the primary provider, and through the fallback provider only when the primary certainly
 * did not accept the message (EmailSendException.Outcome.NOT_SENT). When the primary may already have accepted it
 * (UNKNOWN: a timeout or a server error after the request went out) nothing is resent, so nobody gets the same
 * email twice. Chosen with environment variables, no code change:
 *   EMAIL_PRIMARY_PROVIDER  = BREVO (default) | SES
 *   EMAIL_FALLBACK_PROVIDER = SES | BREVO | NONE (default: none)
 *   EMAIL_MODE              = FAILOVER (default: primary first) | ALTERNATE (Brevo and SES take turns, each the other's fallback)
 *   EMAIL_SES_SHARE         = 0..100, ALTERNATE only: share of emails that go to SES first (default 50)
 */
@Service
public class EmailDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EmailDispatcher.class);

    /** FAILOVER: always the primary first. ALTERNATE: Brevo and SES take turns going first, each the other's fallback. */
    public enum Mode { FAILOVER, ALTERNATE }

    private final Map<EmailProviderType, EmailProvider> providers = new EnumMap<>(EmailProviderType.class);
    private final EmailProviderType primary;
    private final EmailProviderType fallback;
    private final Mode mode;
    /** ALTERNATE only: the share of emails (0..100) that go to SES first; the rest go to Brevo first. */
    private final int sesShare;
    private final java.util.concurrent.atomic.AtomicLong turn = new java.util.concurrent.atomic.AtomicLong();

    public EmailDispatcher(List<EmailProvider> all,
                           @Value("${app.email.primary:BREVO}") String primary,
                           @Value("${app.email.fallback:}") String fallback,
                           @Value("${app.email.mode:FAILOVER}") String mode,
                           @Value("${app.email.ses-share:50}") int sesShare) {
        for (EmailProvider p : all) providers.put(p.type(), p);
        Mode m;
        try {
            m = mode == null || mode.isBlank() ? Mode.FAILOVER : Mode.valueOf(mode.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown EMAIL_MODE '" + mode + "'. Use FAILOVER or ALTERNATE.");
        }
        this.mode = m;
        if (sesShare < 0 || sesShare > 100) throw new IllegalArgumentException("EMAIL_SES_SHARE must be 0 to 100.");
        this.sesShare = sesShare;
        EmailProviderType p = EmailProviderType.parse(primary);
        this.primary = p == null ? EmailProviderType.BREVO : p;
        EmailProviderType f = EmailProviderType.parse(fallback);
        if (f == this.primary) {
            log.warn("EMAIL_FALLBACK_PROVIDER is the same as the primary ({}): no fallback", f);
            f = null;
        }
        // Taking turns needs both providers: each one is the other's fallback
        if (m == Mode.ALTERNATE) f = this.primary == EmailProviderType.BREVO ? EmailProviderType.SES : EmailProviderType.BREVO;
        this.fallback = f;
    }

    public Mode mode() {
        return mode;
    }

    /**
     * Which provider goes first for the next email, and which one catches it if that one certainly failed.
     * ALTERNATE spreads them evenly by the SES share (50 = SES, Brevo, SES, Brevo ...).
     */
    EmailProviderType[] order() {
        if (mode == Mode.FAILOVER) return new EmailProviderType[]{primary, fallback};
        long n = turn.incrementAndGet();
        boolean ses = (n * sesShare) / 100 > ((n - 1) * sesShare) / 100;
        return ses ? new EmailProviderType[]{EmailProviderType.SES, EmailProviderType.BREVO}
                : new EmailProviderType[]{EmailProviderType.BREVO, EmailProviderType.SES};
    }

    public EmailProviderType primary() {
        return primary;
    }

    public EmailProviderType fallback() {
        return fallback;
    }

    /** Says in the log at start-up which providers are in use and whether they have their settings (no secrets). */
    @EventListener(ApplicationReadyEvent.class)
    public void describe() {
        log.info("Email: mode {}{}, primary {} ({}), fallback {}", mode, mode == Mode.ALTERNATE ? " (SES first for " + sesShare + "% of emails)" : "", primary, provider(primary).isConfigured() ? "configured" : "NOT configured",
                fallback == null ? "none" : fallback + " (" + (provider(fallback).isConfigured() ? "configured" : "NOT configured") + ")");
        boolean sesUsed = primary == EmailProviderType.SES || fallback == EmailProviderType.SES;
        if (sesUsed && provider(EmailProviderType.SES) instanceof SesEmailProvider ses && ses.isConfigured()) {
            // Off the start-up thread: makes a sandbox / unverified-domain state visible in the log
            Thread.ofVirtual().name("ses-status").start(ses::logStatus);
        }
    }

    private EmailProvider provider(EmailProviderType t) {
        EmailProvider p = providers.get(t);
        if (p == null) throw new IllegalStateException("No email provider " + t + " in the application");
        return p;
    }

    /**
     * Sends through the primary, then the fallback if the primary certainly didn't send it.
     * Returns "PROVIDER:messageId". Throws the last EmailSendException when no provider sent it.
     */
    public String send(EmailMessage m) {
        EmailProviderType[] order = order();
        EmailProviderType firstType = order[0];
        EmailProviderType backup = order[1];
        try {
            return sendVia(firstType, m);
        } catch (EmailSendException first) {
            if (backup == null) throw first;
            if (!first.safeToRetryElsewhere()) {
                log.warn("Email [{}] to {}: {} may already have accepted it ({}), so it is NOT resent through {} (avoids a duplicate)",
                        m.category(), m.maskedTo(), firstType, first.reason(), backup);
                throw first;
            }
            EmailProvider fb = provider(backup);
            if (!fb.isConfigured()) {
                log.warn("Email [{}] to {}: fallback {} isn't configured, nothing else to try", m.category(), m.maskedTo(), backup);
                throw first;
            }
            log.warn("Email [{}] to {}: {} didn't send it ({}); trying {}", m.category(), m.maskedTo(), firstType, first.reason(), backup);
            try {
                return sendVia(backup, m);
            } catch (EmailSendException second) {
                second.addSuppressed(first);
                throw second;
            }
        }
    }

    /** Sends through one named provider only, with no fallback (admin test). */
    public String sendVia(EmailProviderType type, EmailMessage m) {
        EmailProvider p = provider(type);
        long t0 = System.nanoTime();
        try {
            String id = p.send(m);
            log.info("Email [{}] to {} sent via {} in {} ms{}", m.category(), m.maskedTo(), type, (System.nanoTime() - t0) / 1_000_000,
                    id == null ? "" : " (id " + id + ")");
            return type + ":" + (id == null ? "" : id);
        } catch (EmailSendException e) {
            log.warn("Email [{}] to {} via {} failed ({}): {}", m.category(), m.maskedTo(), type, e.outcome(), e.reason());
            throw e;
        } catch (RuntimeException e) {
            // A provider bug or an unexpected library error: treat as "may have been sent"
            log.warn("Email [{}] to {} via {} failed unexpectedly: {}", m.category(), m.maskedTo(), type, e.getClass().getSimpleName());
            throw new EmailSendException(type, EmailSendException.Outcome.UNKNOWN, "unexpected error: " + e.getClass().getSimpleName(), e);
        }
    }

    /** For the admin panel: which provider is used, and whether each one has its settings. No secrets. */
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", mode.name());
        if (mode == Mode.ALTERNATE) out.put("sesShare", sesShare);
        out.put("primary", primary.name());
        out.put("fallback", fallback == null ? null : fallback.name());
        Map<String, Object> list = new LinkedHashMap<>();
        for (EmailProvider p : providers.values()) list.put(p.type().name(), Map.of("configured", p.isConfigured()));
        out.put("providers", list);
        return out;
    }
}
