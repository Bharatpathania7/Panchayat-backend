package com.panchayat.backend.email;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;
import software.amazon.awssdk.services.sesv2.model.SesV2Exception;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Each provider on its own: what it sends, and whether a failure means "certainly not sent" or "maybe sent". */
class EmailProvidersTest {

    private final EmailMessage msg = new EmailMessage("someone@example.com", "Your Panchayat verification code", "code 123456", "<p>123456</p>", "OTP");

    /* ---------------- Brevo (SMTP) ---------------- */

    /** Captures the message instead of talking to an SMTP server. */
    static final class CapturingSender extends JavaMailSenderImpl {
        MimeMessage last;
        RuntimeException fail;

        @Override
        public MimeMessage createMimeMessage() {
            return new MimeMessage(Session.getInstance(new Properties()));
        }

        @Override
        public void send(MimeMessage mimeMessage) {
            if (fail != null) throw fail;
            last = mimeMessage;
        }
    }

    @Test
    void brevoSendsTheSameMessageAsBefore() throws Exception {
        CapturingSender s = new CapturingSender();
        BrevoSmtpEmailProvider brevo = new BrevoSmtpEmailProvider(s, "login@smtp-brevo.com", "no-reply@panchayat.world", "Panchayat");
        brevo.send(msg);
        assertThat(((InternetAddress) s.last.getFrom()[0]).getAddress()).isEqualTo("no-reply@panchayat.world");
        assertThat(((InternetAddress) s.last.getFrom()[0]).getPersonal()).isEqualTo("Panchayat");
        assertThat(s.last.getSubject()).isEqualTo("Your Panchayat verification code");
        assertThat(s.last.getAllRecipients()[0].toString()).isEqualTo("someone@example.com");
        assertThat(s.last.getContent()).isInstanceOf(Multipart.class);
    }

    @Test
    void brevoWithoutMailFromUsesTheLogin() {
        BrevoSmtpEmailProvider brevo = new BrevoSmtpEmailProvider(new CapturingSender(), "me@gmail.com", "", "Panchayat");
        assertThat(brevo.fromAddress()).isEqualTo("me@gmail.com");
        assertThat(new BrevoSmtpEmailProvider(new CapturingSender(), "", "", "Panchayat").isConfigured()).isFalse();
    }

    @Test
    void brevoFailuresAreClassified() {
        CapturingSender s = new CapturingSender();
        BrevoSmtpEmailProvider brevo = new BrevoSmtpEmailProvider(s, "login", "no-reply@panchayat.world", "Panchayat");

        s.fail = new MailAuthenticationException(new AuthenticationFailedException("535"));
        assertThatThrownBy(() -> brevo.send(msg)).isInstanceOf(EmailSendException.class)
                .satisfies(e -> assertThat(((EmailSendException) e).outcome()).isEqualTo(EmailSendException.Outcome.NOT_SENT));

        s.fail = new MailSendException("connect", new ConnectException("Connection refused"));
        assertThatThrownBy(() -> brevo.send(msg)).satisfies(e -> assertThat(((EmailSendException) e).outcome()).isEqualTo(EmailSendException.Outcome.NOT_SENT));

        s.fail = new MailSendException("connect", new SocketTimeoutException("connect timed out"));
        assertThatThrownBy(() -> brevo.send(msg)).satisfies(e -> assertThat(((EmailSendException) e).outcome()).isEqualTo(EmailSendException.Outcome.NOT_SENT));

        // A read timeout after the message went out: maybe accepted, so never resent elsewhere
        s.fail = new MailSendException("read", new SocketTimeoutException("Read timed out"));
        assertThatThrownBy(() -> brevo.send(msg)).satisfies(e -> assertThat(((EmailSendException) e).outcome()).isEqualTo(EmailSendException.Outcome.UNKNOWN));

        s.fail = new MailSendException("odd");
        assertThatThrownBy(() -> brevo.send(msg)).satisfies(e -> assertThat(((EmailSendException) e).outcome()).isEqualTo(EmailSendException.Outcome.UNKNOWN));
    }

    /* ---------------- Amazon SES ---------------- */

    private static final Map<String, String> KEYS = Map.of("AWS_ACCESS_KEY_ID", "AKIATEST", "AWS_SECRET_ACCESS_KEY", "secret");

    private SesEmailProvider ses(SesV2Client client, Map<String, String> env) {
        return new SesEmailProvider("us-east-1", "support@panchayat.world", "Panchayat", "", env::get, () -> client);
    }

    @Test
    void sesSendsUtf8TextAndHtmlFromTheVerifiedSender() {
        SesV2Client client = mock(SesV2Client.class);
        AtomicReference<SendEmailRequest> seen = new AtomicReference<>();
        when(client.sendEmail(any(SendEmailRequest.class))).thenAnswer(i -> {
            seen.set(i.getArgument(0));
            return SendEmailResponse.builder().messageId("ses-1").build();
        });
        assertThat(ses(client, KEYS).send(msg)).isEqualTo("ses-1");
        SendEmailRequest r = seen.get();
        assertThat(r.fromEmailAddress()).isEqualTo("Panchayat <support@panchayat.world>");
        assertThat(r.destination().toAddresses()).containsExactly("someone@example.com");
        assertThat(r.content().simple().subject().data()).isEqualTo("Your Panchayat verification code");
        assertThat(r.content().simple().body().html().charset()).isEqualTo("UTF-8");
        assertThat(r.content().simple().body().text().data()).isEqualTo("code 123456");
        assertThat(r.configurationSetName()).isNull();
    }

    @Test
    void sesWithoutKeysIsNotConfiguredAndNeverCallsAws() {
        SesV2Client client = mock(SesV2Client.class);
        SesEmailProvider p = ses(client, Map.of());
        assertThat(p.isConfigured()).isFalse();
        assertThatThrownBy(() -> p.send(msg)).satisfies(e -> assertThat(((EmailSendException) e).outcome()).isEqualTo(EmailSendException.Outcome.NOT_SENT));
        assertThat(p.status()).containsEntry("configured", false).containsEntry("readyForProduction", false);
    }

    private static SesV2Exception sesError(int status, String code, String message) {
        return (SesV2Exception) SesV2Exception.builder().statusCode(status)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode(code).errorMessage(message).build()).build();
    }

    @Test
    void sesSandboxAndVerificationRefusalsAreNotSentWithAClearReason() {
        SesEmailProvider p = ses(mock(SesV2Client.class), KEYS);
        EmailSendException e = p.classify(sesError(400, "MessageRejected", "Email address is not verified. The following identities failed the check: someone@example.com"));
        assertThat(e.outcome()).isEqualTo(EmailSendException.Outcome.NOT_SENT);
        assertThat(e.reason()).contains("sandbox");
        assertThat(p.classify(sesError(400, "MailFromDomainNotVerifiedException", "x")).reason()).contains("MAIL FROM");
        assertThat(p.classify(sesError(429, "TooManyRequestsException", "x")).outcome()).isEqualTo(EmailSendException.Outcome.NOT_SENT);
        assertThat(p.classify(sesError(403, "AccessDeniedException", "x")).reason()).contains("ses:SendEmail");
    }

    @Test
    void sesUnclearFailuresAreNeverResent() {
        SesEmailProvider p = ses(mock(SesV2Client.class), KEYS);
        assertThat(p.classify(sesError(500, "InternalFailure", "x")).outcome()).isEqualTo(EmailSendException.Outcome.UNKNOWN);
        assertThat(p.classify(ApiCallTimeoutException.create(15000)).outcome()).isEqualTo(EmailSendException.Outcome.UNKNOWN);
        assertThat(p.classify(SdkClientException.create("io", new SocketTimeoutException("Read timed out"))).outcome()).isEqualTo(EmailSendException.Outcome.UNKNOWN);
        assertThat(p.classify(SdkClientException.create("io", new ConnectException("refused"))).outcome()).isEqualTo(EmailSendException.Outcome.NOT_SENT);
        assertThat(p.classify(SdkClientException.create("Unable to load credentials from any of the providers")).outcome()).isEqualTo(EmailSendException.Outcome.NOT_SENT);
    }

    @Test
    void sesErrorsNeverCarryTheSecret() {
        SesEmailProvider p = ses(mock(SesV2Client.class), KEYS);
        EmailSendException e = p.classify(sesError(403, "SignatureDoesNotMatch", "bad signature"));
        assertThat(e.getMessage()).doesNotContain("secret").doesNotContain("AKIATEST");
    }
}
