package com.panchayat.backend.email;

import com.panchayat.backend.auth.service.EmailService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Email providers, Super Admin only:
 *   GET  /api/v1/admin/email/status            primary / fallback, which providers have settings, and SES's own account
 *                                               state (sandbox or production, domain and MAIL FROM verification)
 *   POST /api/v1/admin/email/test { to, provider: BREVO | SES }   one test email through that provider only (no fallback)
 * Nothing here returns or logs a credential.
 */
@RestController
@RequestMapping("/api/v1/admin/email")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminEmailController {

    private final EmailDispatcher dispatcher;
    private final SesEmailProvider ses;
    private final EmailService email;

    public AdminEmailController(EmailDispatcher dispatcher, SesEmailProvider ses, EmailService email) {
        this.dispatcher = dispatcher;
        this.ses = ses;
        this.email = email;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>(dispatcher.status());
        out.put("ses", ses.status());
        return out;
    }

    public record TestRequest(String to, String provider) {
    }

    @PostMapping("/test")
    public Map<String, Object> test(@RequestBody TestRequest body) {
        if (body == null || body.to() == null || !body.to().strip().matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter an email address to send the test to.");
        EmailProviderType type;
        try {
            type = EmailProviderType.parse(body.provider());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        if (type == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose BREVO or SES.");
        String id = email.sendTest(body.to().strip(), type);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sent", true);
        out.put("provider", type.name());
        out.put("messageId", id.substring(id.indexOf(':') + 1));
        return out;
    }

    /** A provider failure as a readable answer (provider, whether it might have been sent, the reason): never a secret. */
    @ExceptionHandler(EmailSendException.class)
    public ResponseEntity<Map<String, Object>> failed(EmailSendException e) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sent", false);
        out.put("provider", e.provider().name());
        out.put("outcome", e.outcome().name());
        out.put("message", e.reason());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(out);
    }
}
