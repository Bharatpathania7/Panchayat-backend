package com.panchayat.backend.email;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Provider choice, fallback only when the message certainly wasn't sent, and taking turns. No real email is sent. */
class EmailDispatcherTest {

    /** A provider that records what it was asked to send and fails the way it's told to. */
    static final class FakeProvider implements EmailProvider {
        final EmailProviderType type;
        boolean configured = true;
        EmailSendException.Outcome failWith;
        final List<EmailMessage> sent = new ArrayList<>();
        int calls;

        FakeProvider(EmailProviderType type) {
            this.type = type;
        }

        @Override
        public EmailProviderType type() {
            return type;
        }

        @Override
        public boolean isConfigured() {
            return configured;
        }

        @Override
        public String send(EmailMessage message) {
            calls++;
            if (!configured) throw new EmailSendException(type, EmailSendException.Outcome.NOT_SENT, "not configured", null);
            if (failWith != null) throw new EmailSendException(type, failWith, "fake failure", null);
            sent.add(message);
            return "id-" + type + "-" + sent.size();
        }
    }

    private final FakeProvider brevo = new FakeProvider(EmailProviderType.BREVO);
    private final FakeProvider ses = new FakeProvider(EmailProviderType.SES);
    private final EmailMessage msg = new EmailMessage("someone@example.com", "Subject", "text", "<p>html</p>", "OTP");

    private EmailDispatcher dispatcher(String primary, String fallback, String mode, int share) {
        return new EmailDispatcher(List.of(brevo, ses), primary, fallback, mode, share);
    }

    @Test
    void defaultsToBrevoOnlyLikeBefore() {
        EmailDispatcher d = dispatcher("", "", "", 50);
        assertThat(d.primary()).isEqualTo(EmailProviderType.BREVO);
        assertThat(d.fallback()).isNull();
        assertThat(d.send(msg)).startsWith("BREVO:");
        assertThat(ses.calls).isZero();
    }

    @Test
    void primarySesWorksAlone() {
        EmailDispatcher d = dispatcher("SES", "NONE", "FAILOVER", 50);
        assertThat(d.send(msg)).startsWith("SES:");
        assertThat(brevo.calls).isZero();
    }

    @Test
    void fallbackWhenPrimaryCertainlyDidNotSend() {
        brevo.failWith = EmailSendException.Outcome.NOT_SENT;
        EmailDispatcher d = dispatcher("BREVO", "SES", "FAILOVER", 50);
        assertThat(d.send(msg)).startsWith("SES:");
        assertThat(ses.sent).hasSize(1);
    }

    @Test
    void noFallbackWhenPrimaryMayHaveSentIt_noDuplicate() {
        brevo.failWith = EmailSendException.Outcome.UNKNOWN;
        EmailDispatcher d = dispatcher("BREVO", "SES", "FAILOVER", 50);
        assertThatThrownBy(() -> d.send(msg)).isInstanceOf(EmailSendException.class)
                .satisfies(e -> assertThat(((EmailSendException) e).outcome()).isEqualTo(EmailSendException.Outcome.UNKNOWN));
        assertThat(ses.calls).isZero();
    }

    @Test
    void unconfiguredPrimaryFallsBack() {
        ses.configured = false;
        EmailDispatcher d = dispatcher("SES", "BREVO", "FAILOVER", 50);
        assertThat(d.send(msg)).startsWith("BREVO:");
    }

    @Test
    void unconfiguredFallbackIsNotCalled() {
        brevo.failWith = EmailSendException.Outcome.NOT_SENT;
        ses.configured = false;
        EmailDispatcher d = dispatcher("BREVO", "SES", "FAILOVER", 50);
        assertThatThrownBy(() -> d.send(msg)).isInstanceOf(EmailSendException.class);
        assertThat(ses.calls).isZero();
    }

    @Test
    void bothFailReportsTheLastOneWithTheFirstAttached() {
        brevo.failWith = EmailSendException.Outcome.NOT_SENT;
        ses.failWith = EmailSendException.Outcome.NOT_SENT;
        EmailDispatcher d = dispatcher("BREVO", "SES", "FAILOVER", 50);
        assertThatThrownBy(() -> d.send(msg)).isInstanceOf(EmailSendException.class)
                .satisfies(e -> {
                    assertThat(((EmailSendException) e).provider()).isEqualTo(EmailProviderType.SES);
                    assertThat(e.getSuppressed()).hasSize(1);
                });
    }

    @Test
    void sameFallbackAsPrimaryMeansNoFallback() {
        EmailDispatcher d = dispatcher("SES", "SES", "FAILOVER", 50);
        assertThat(d.fallback()).isNull();
    }

    @Test
    void alternateTakesTurnsEvenly() {
        EmailDispatcher d = dispatcher("BREVO", "", "ALTERNATE", 50);
        for (int i = 0; i < 10; i++) d.send(msg);
        assertThat(ses.sent).hasSize(5);
        assertThat(brevo.sent).hasSize(5);
    }

    @Test
    void alternateShareControlsTheSplit() {
        EmailDispatcher d = dispatcher("BREVO", "", "ALTERNATE", 30);
        for (int i = 0; i < 100; i++) d.send(msg);
        assertThat(ses.sent).hasSize(30);
        assertThat(brevo.sent).hasSize(70);
    }

    @Test
    void alternateShareZeroAndHundred() {
        EmailDispatcher none = dispatcher("BREVO", "", "ALTERNATE", 0);
        for (int i = 0; i < 5; i++) none.send(msg);
        assertThat(ses.sent).isEmpty();
        EmailDispatcher all = dispatcher("BREVO", "", "ALTERNATE", 100);
        for (int i = 0; i < 5; i++) all.send(msg);
        assertThat(ses.sent).hasSize(5);
    }

    @Test
    void alternateOtherProviderCatchesAFailure() {
        ses.failWith = EmailSendException.Outcome.NOT_SENT; // e.g. SES sandbox refuses
        EmailDispatcher d = dispatcher("BREVO", "", "ALTERNATE", 50);
        for (int i = 0; i < 6; i++) d.send(msg);
        assertThat(brevo.sent).hasSize(6); // every email still arrived, once
    }

    @Test
    void alternateNeverResendsAfterUnknown() {
        ses.failWith = EmailSendException.Outcome.UNKNOWN;
        EmailDispatcher d = dispatcher("BREVO", "", "ALTERNATE", 100);
        assertThatThrownBy(() -> d.send(msg)).isInstanceOf(EmailSendException.class);
        assertThat(brevo.calls).isZero();
    }

    @Test
    void sendViaUsesOnlyThatProvider() {
        brevo.failWith = EmailSendException.Outcome.NOT_SENT;
        EmailDispatcher d = dispatcher("BREVO", "SES", "FAILOVER", 50);
        assertThatThrownBy(() -> d.sendVia(EmailProviderType.BREVO, msg)).isInstanceOf(EmailSendException.class);
        assertThat(ses.calls).isZero();
    }

    @Test
    void badSettingsFailAtStartup() {
        assertThatThrownBy(() -> dispatcher("MAILGUN", "", "FAILOVER", 50)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dispatcher("BREVO", "", "RANDOM", 50)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dispatcher("BREVO", "", "ALTERNATE", 150)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recipientIsMaskedInLogs() {
        assertThat(msg.maskedTo()).isEqualTo("so***@example.com");
        assertThat(EmailMessage.mask("a@b.com")).isEqualTo("a*@b.com");
    }
}
