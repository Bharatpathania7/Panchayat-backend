package com.panchayat.backend.email;

import jakarta.annotation.PreDestroy;
import jakarta.mail.internet.InternetAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.EnvironmentVariableCredentialsProvider;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.GetAccountResponse;
import software.amazon.awssdk.services.sesv2.model.GetEmailIdentityResponse;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SesV2Exception;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Amazon SES through its API v2 (AWS SDK for Java 2.x). Credentials come ONLY from the environment variables
 * AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY (never from files or code); the region from AWS_REGION (us-east-1).
 * The client is made the first time it is needed, so a server without AWS keys starts and runs exactly as before.
 * Retries are off: one call = at most one email; the dispatcher decides about the fallback provider.
 *
 * While the domain identity isn't verified or the account is still in the SES sandbox, SES refuses (MessageRejected /
 * MailFromDomainNotVerified, ...): those come back as NOT_SENT with a clear reason, so the fallback provider can send.
 */
@Component
public class SesEmailProvider implements EmailProvider {

    private static final Logger log = LoggerFactory.getLogger(SesEmailProvider.class);

    private final String region;
    private final String fromAddress;
    private final String fromName;
    private final String configurationSet;
    private final Function<String, String> env;
    private final Supplier<SesV2Client> clientFactory;
    private volatile SesV2Client client;

    @org.springframework.beans.factory.annotation.Autowired
    public SesEmailProvider(
            @Value("${app.email.ses.region:us-east-1}") String region,
            @Value("${app.email.ses.from:support@panchayat.world}") String from,
            @Value("${app.mail.from-name:Panchayat}") String fromName,
            @Value("${app.email.ses.configuration-set:}") String configurationSet
    ) {
        this(region, from, fromName, configurationSet, System::getenv, null);
    }

    /** For tests: a fake environment and a fake client. */
    SesEmailProvider(String region, String from, String fromName, String configurationSet, Function<String, String> env, Supplier<SesV2Client> clientFactory) {
        this.region = region == null || region.isBlank() ? "us-east-1" : region.strip();
        this.fromAddress = from == null ? "" : from.strip();
        this.fromName = fromName == null || fromName.isBlank() ? "Panchayat" : fromName.strip();
        this.configurationSet = configurationSet == null ? "" : configurationSet.strip();
        this.env = env;
        this.clientFactory = clientFactory != null ? clientFactory : this::buildClient;
    }

    @Override
    public EmailProviderType type() {
        return EmailProviderType.SES;
    }

    @Override
    public boolean isConfigured() {
        return present("AWS_ACCESS_KEY_ID") && present("AWS_SECRET_ACCESS_KEY") && !fromAddress.isEmpty();
    }

    private boolean present(String name) {
        String v = env.apply(name);
        return v != null && !v.isBlank();
    }

    public String region() {
        return region;
    }

    public String fromAddress() {
        return fromAddress;
    }

    /** The domain part of the sender (panchayat.world): the identity SES must have verified. */
    public String senderDomain() {
        int at = fromAddress.indexOf('@');
        return at < 0 ? fromAddress : fromAddress.substring(at + 1);
    }

    private SesV2Client buildClient() {
        return SesV2Client.builder()
                .region(Region.of(region))
                // Environment variables only, as required: AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY
                .credentialsProvider(EnvironmentVariableCredentialsProvider.create())
                .httpClientBuilder(Apache5HttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(5))
                        .socketTimeout(Duration.ofSeconds(10)))
                .overrideConfiguration(c -> c
                        // No automatic retries: a retry after an unclear failure could send the same email twice
                        .retryStrategy(AwsRetryStrategy.doNotRetry())
                        .apiCallTimeout(Duration.ofSeconds(15)))
                .build();
    }

    private SesV2Client client() {
        SesV2Client c = client;
        if (c == null) {
            synchronized (this) {
                if (client == null) client = clientFactory.get();
                c = client;
            }
        }
        return c;
    }

    @PreDestroy
    void close() {
        SesV2Client c = client;
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
                // shutting down
            }
        }
    }

    @Override
    public String send(EmailMessage m) {
        if (!isConfigured()) {
            throw new EmailSendException(type(), EmailSendException.Outcome.NOT_SENT, "AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY or the SES sender isn't set", null);
        }
        String from;
        try {
            // "Panchayat <support@panchayat.world>", encoded properly if the name has non-ASCII letters
            from = new InternetAddress(fromAddress, fromName, StandardCharsets.UTF_8.name()).toString();
        } catch (java.io.UnsupportedEncodingException e) {
            from = fromAddress;
        }
        Body.Builder body = Body.builder();
        if (m.text() != null && !m.text().isBlank()) body.text(utf8(m.text()));
        if (m.html() != null && !m.html().isBlank()) body.html(utf8(m.html()));
        SendEmailRequest.Builder req = SendEmailRequest.builder()
                .fromEmailAddress(from)
                .destination(d -> d.toAddresses(m.to()))
                .content(EmailContent.builder().simple(Message.builder().subject(utf8(m.subject())).body(body.build()).build()).build());
        if (!configurationSet.isEmpty()) req.configurationSetName(configurationSet);
        try {
            return client().sendEmail(req.build()).messageId();
        } catch (RuntimeException e) {
            throw classify(e);
        }
    }

    private static Content utf8(String s) {
        return Content.builder().data(s).charset("UTF-8").build();
    }

    /** Was the message certainly not accepted by SES (safe for the fallback), or might it have been? */
    EmailSendException classify(RuntimeException e) {
        if (e instanceof EmailSendException ese) return ese;
        if (e instanceof SesV2Exception se) {
            int status = se.statusCode();
            String code = se.awsErrorDetails() == null ? null : se.awsErrorDetails().errorCode();
            String msg = se.awsErrorDetails() == null ? "" : String.valueOf(se.awsErrorDetails().errorMessage());
            if (status >= 400 && status < 500) {
                // SES answered and refused: nothing was sent
                return notSent(explain(code, msg, status), e);
            }
            return new EmailSendException(type(), EmailSendException.Outcome.UNKNOWN, "SES server error " + status + (code == null ? "" : " " + code), e);
        }
        if (e instanceof ApiCallTimeoutException || e instanceof ApiCallAttemptTimeoutException) {
            return new EmailSendException(type(), EmailSendException.Outcome.UNKNOWN, "timed out waiting for SES's answer", e);
        }
        if (e instanceof SdkClientException) {
            String m = e.getMessage() == null ? "" : e.getMessage();
            if (m.contains("Unable to load credentials") || m.toLowerCase(Locale.ROOT).contains("credential")) {
                return notSent("AWS credentials couldn't be loaded from AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY", e);
            }
            for (Throwable t = e.getCause(); t != null; t = t.getCause()) {
                if (t instanceof UnknownHostException || t instanceof ConnectException || t instanceof NoRouteToHostException
                        || t.getClass().getName().endsWith("ConnectTimeoutException") || t.getClass().getName().endsWith("HttpHostConnectException")) {
                    return notSent("couldn't connect to SES (" + region + ")", e);
                }
                if (t instanceof SocketTimeoutException) {
                    return new EmailSendException(type(), EmailSendException.Outcome.UNKNOWN, "timed out waiting for SES's answer", e);
                }
            }
        }
        return new EmailSendException(type(), EmailSendException.Outcome.UNKNOWN, "unexpected SES error: " + e.getClass().getSimpleName(), e);
    }

    /** SES error codes in plain words, with the sandbox / verification cases spelled out. */
    static String explain(String code, String message, int status) {
        String c = code == null ? "" : code;
        String m = message == null ? "" : message;
        if (m.toLowerCase(Locale.ROOT).contains("not verified")) {
            return "SES refused: " + m + " (domain identity not verified yet, or the account is in the SES sandbox where only verified recipients can receive mail)";
        }
        return switch (c) {
            case "MessageRejected" -> "SES rejected the message: " + m;
            case "MailFromDomainNotVerifiedException", "MailFromDomainNotVerified" -> "SES: the custom MAIL FROM domain isn't verified yet";
            case "AccountSuspendedException", "AccountSuspended" -> "SES: sending is suspended on this AWS account";
            case "SendingPausedException", "SendingPaused" -> "SES: sending is paused for this account or configuration set";
            case "TooManyRequestsException", "Throttling", "ThrottlingException" -> "SES: too many requests right now (sending rate limit)";
            case "LimitExceededException" -> "SES: daily sending quota reached";
            case "NotFoundException" -> "SES: the sender identity or configuration set wasn't found";
            case "AccessDeniedException", "AccessDenied" -> "SES: the AWS user isn't allowed to send (IAM policy needs ses:SendEmail)";
            case "UnrecognizedClientException", "InvalidClientTokenId", "SignatureDoesNotMatch" -> "SES: the AWS access key is wrong or inactive";
            default -> "SES refused (" + status + (c.isEmpty() ? "" : " " + c) + "): " + m;
        };
    }

    private EmailSendException notSent(String reason, Throwable cause) {
        return new EmailSendException(type(), EmailSendException.Outcome.NOT_SENT, reason, cause);
    }

    /**
     * What SES itself says about this account and the sender's domain: sandbox or production access, sending enabled,
     * quota, and whether the domain (DKIM) and the custom MAIL FROM are verified. Needs ses:GetAccount and
     * ses:GetEmailIdentity. Never throws: a problem is reported in "error".
     */
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("configured", isConfigured());
        out.put("region", region);
        out.put("from", fromAddress);
        if (!isConfigured()) {
            out.put("readyForProduction", false);
            out.put("note", "AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY are not set on this server");
            return out;
        }
        boolean production = false, sending = false, verified = false;
        try {
            GetAccountResponse a = client().getAccount(r -> {});
            production = Boolean.TRUE.equals(a.productionAccessEnabled());
            sending = Boolean.TRUE.equals(a.sendingEnabled());
            out.put("productionAccess", production);
            out.put("sandbox", !production);
            out.put("sendingEnabled", sending);
            out.put("enforcementStatus", a.enforcementStatus());
            if (a.sendQuota() != null) {
                out.put("max24HourSend", a.sendQuota().max24HourSend());
                out.put("sentLast24Hours", a.sendQuota().sentLast24Hours());
                out.put("maxSendRate", a.sendQuota().maxSendRate());
            }
        } catch (RuntimeException e) {
            out.put("accountError", classify(e).reason());
        }
        try {
            GetEmailIdentityResponse id = client().getEmailIdentity(r -> r.emailIdentity(senderDomain()));
            verified = Boolean.TRUE.equals(id.verifiedForSendingStatus());
            out.put("identity", senderDomain());
            out.put("identityVerified", verified);
            out.put("identityStatus", id.verificationStatusAsString());
            if (id.dkimAttributes() != null) out.put("dkimStatus", id.dkimAttributes().statusAsString());
            if (id.mailFromAttributes() != null) {
                out.put("mailFromDomain", id.mailFromAttributes().mailFromDomain());
                out.put("mailFromStatus", id.mailFromAttributes().mailFromDomainStatusAsString());
            }
        } catch (RuntimeException e) {
            out.put("identityError", classify(e).reason());
        }
        boolean ready = production && sending && verified;
        out.put("readyForProduction", ready);
        if (!ready) {
            StringBuilder why = new StringBuilder();
            // Only say what SES actually told us; if it couldn't be asked, say that instead of guessing
            if (out.containsKey("accountError") || out.containsKey("identityError")) {
                why.append("Couldn't read the SES status: ").append(out.getOrDefault("accountError", out.get("identityError"))).append(". ");
            }
            if (out.containsKey("identityVerified") && !verified) why.append("Domain ").append(senderDomain()).append(" is not verified for sending yet (SES: ").append(out.get("identityStatus")).append("). ");
            if (out.containsKey("productionAccess") && !production) why.append("Account is in the SES sandbox: only verified recipient addresses can receive mail until production access is granted. ");
            if (out.containsKey("sendingEnabled") && !sending) why.append("Sending is disabled on the account. ");
            out.put("note", why.toString().strip());
        }
        return out;
    }

    /** Logged once when SES is chosen: makes sandbox / unverified state visible in the server log. Never throws. */
    public void logStatus() {
        try {
            Map<String, Object> s = status();
            if (Boolean.TRUE.equals(s.get("readyForProduction"))) log.info("SES ready: region {}, domain {} verified, production access on", region, senderDomain());
            else log.warn("SES is NOT ready for production: {} (details: {})", s.get("note"), s);
        } catch (Exception e) {
            log.warn("SES status check failed: {}", e.getClass().getSimpleName());
        }
    }
}
