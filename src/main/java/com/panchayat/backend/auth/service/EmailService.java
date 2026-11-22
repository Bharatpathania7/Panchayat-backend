package com.panchayat.backend.auth.service;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.nio.charset.StandardCharsets;

@Service
public class EmailService {

    private final JavaMailSender mailSender;
    private final String fromAddress;
    private final String fromName;
    private final String siteUrl;

    public EmailService(
            JavaMailSender mailSender,
            @Value("${spring.mail.username}") String username,
            @Value("${app.mail.from:}") String from,
            @Value("${app.mail.from-name:Panchayat}") String fromName,
            @Value("${app.frontend-url:https://panchayat.world}") String siteUrl
    ) {
        this.mailSender = mailSender;
        // A domain sender (MAIL_FROM) when set, else the login address (Gmail). Shown as "Panchayat <no-reply@...>".
        this.fromAddress = from == null || from.isBlank() ? username : from.strip();
        this.fromName = fromName == null || fromName.isBlank() ? "Panchayat" : fromName.strip();
        this.siteUrl = siteUrl == null || siteUrl.isBlank() ? "https://panchayat.world" : siteUrl.strip().replaceAll("/+$", "");
    }

    public void sendOtp(String recipientEmail, String otp) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(new InternetAddress(fromAddress, fromName, StandardCharsets.UTF_8.name()));
            helper.setTo(recipientEmail);
            helper.setSubject("Your Panchayat verification code");
            // Plain text first, HTML second: mail apps show the last part they understand
            helper.setText(plainText(otp), otpHtml(otp));
            mailSender.send(message);
        } catch (jakarta.mail.MessagingException | java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException("Could not build the verification email", e);
        }
    }

    public void sendMaintenanceNotice(String recipientEmail, String message) {
        try {
            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, StandardCharsets.UTF_8.name());
            helper.setFrom(new InternetAddress(fromAddress, fromName, StandardCharsets.UTF_8.name()));
            helper.setTo(recipientEmail);
            helper.setSubject("Panchayat is briefly closing for maintenance");
            helper.setText(maintenancePlainText(message), maintenanceHtml(message));
            mailSender.send(mime);
        } catch (jakarta.mail.MessagingException | java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException("Could not build the maintenance email", e);
        }
    }

    static String maintenancePlainText(String message) {
        return "Hello,\n\n" + message + "\n\nThanks for your patience.\n\nRegards,\nPanchayat Team";
    }

    String maintenanceHtml(String message) {
        String body = HtmlUtils.htmlEscape(message == null ? "" : message);
        String logo = siteUrl + "/brand/panchayat-logo-email.png";
        return "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<meta name=\"color-scheme\" content=\"light\"><title>Panchayat maintenance</title></head>"
                + "<body style=\"margin:0;padding:0;background:#f4efe9;\">"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:#f4efe9;padding:24px 12px;\"><tr><td align=\"center\">"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"max-width:480px;background:#ffffff;border-radius:20px;overflow:hidden;"
                + "box-shadow:0 6px 24px rgba(60,20,20,0.12);font-family:'Segoe UI',Roboto,Helvetica,Arial,sans-serif;\">"
                + "<tr><td align=\"center\" style=\"background:#2a0a14;background-image:linear-gradient(160deg,#4a0f24 0%,#1d0710 100%);padding:30px 20px 24px;\">"
                + "<img src=\"" + logo + "\" width=\"220\" alt=\"Panchayat\" style=\"display:block;width:220px;max-width:70%;height:auto;border:0;\">"
                + "</td></tr>"
                + "<tr><td style=\"padding:32px 28px 8px;color:#2a1a1a;\">"
                + "<div style=\"font-size:20px;font-weight:800;color:#2a0f14;\">We're briefly closing for maintenance</div>"
                + "<div style=\"margin-top:10px;font-size:15px;line-height:1.55;color:#5a4646;\">" + body + "</div></td></tr>"
                + "<tr><td style=\"padding:8px 28px 30px;font-size:13px;line-height:1.6;color:#7a6767;\">Thanks for your patience.</td></tr>"
                + "<tr><td align=\"center\" style=\"background:#faf5ef;border-top:1px solid #efe3d4;padding:18px 20px;font-size:12px;line-height:1.6;color:#9a8a7a;\">"
                + "Panchayat Team<br><a href=\"" + siteUrl + "\" style=\"color:#c0392b;text-decoration:none;\">" + siteUrl.replaceFirst("^https?://", "") + "</a></td></tr>"
                + "</table></td></tr></table></body></html>";
    }

    static String plainText(String otp) {
        return "Hello,\n\n"
                + "Your Panchayat verification code is: " + otp + "\n\n"
                + "This code expires in 2 minutes.\n"
                + "If you did not request this code, please ignore this email.\n\n"
                + "Regards,\nPanchayat Team";
    }

    /** Table layout with inline styles: the only kind of HTML every mail app renders the same way. */
    String otpHtml(String otp) {
        String code = HtmlUtils.htmlEscape(otp == null ? "" : otp);
        StringBuilder digits = new StringBuilder();
        for (char c : code.toCharArray()) {
            digits.append("<td style=\"padding:0 4px;\"><div style=\"width:40px;height:52px;line-height:52px;text-align:center;")
                    .append("font-size:28px;font-weight:800;color:#2a0f14;background:#fff4e4;border:1px solid #f3c98b;border-radius:12px;\">")
                    .append(c).append("</div></td>");
        }
        String logo = siteUrl + "/brand/panchayat-logo-email.png";
        return "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<meta name=\"color-scheme\" content=\"light\"><title>Your Panchayat verification code</title></head>"
                + "<body style=\"margin:0;padding:0;background:#f4efe9;\">"
                + "<div style=\"display:none;max-height:0;overflow:hidden;opacity:0;\">Your code is " + code + ". It expires in 2 minutes.</div>"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:#f4efe9;padding:24px 12px;\"><tr><td align=\"center\">"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"max-width:480px;background:#ffffff;border-radius:20px;overflow:hidden;"
                + "box-shadow:0 6px 24px rgba(60,20,20,0.12);font-family:'Segoe UI',Roboto,Helvetica,Arial,sans-serif;\">"
                // header
                + "<tr><td align=\"center\" style=\"background:#2a0a14;background-image:linear-gradient(160deg,#4a0f24 0%,#1d0710 100%);padding:30px 20px 24px;\">"
                + "<img src=\"" + logo + "\" width=\"220\" alt=\"Panchayat\" style=\"display:block;width:220px;max-width:70%;height:auto;border:0;\">"
                + "</td></tr>"
                // body
                + "<tr><td style=\"padding:32px 28px 8px;color:#2a1a1a;\">"
                + "<div style=\"font-size:20px;font-weight:800;color:#2a0f14;\">Verify your email</div>"
                + "<div style=\"margin-top:10px;font-size:15px;line-height:1.55;color:#5a4646;\">Use this code to finish signing in to Panchayat.</div></td></tr>"
                + "<tr><td align=\"center\" style=\"padding:22px 12px 6px;\"><table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\"><tr>" + digits + "</tr></table></td></tr>"
                + "<tr><td align=\"center\" style=\"padding:14px 28px 0;\"><span style=\"display:inline-block;font-size:13px;font-weight:700;color:#b3331f;background:#fdeceb;border-radius:999px;padding:6px 14px;\">"
                + "Expires in 2 minutes</span></td></tr>"
                + "<tr><td style=\"padding:24px 28px 30px;font-size:13px;line-height:1.6;color:#7a6767;\">"
                + "Never share this code with anyone. Panchayat will never ask you for it.<br>If you did not ask for this code, you can safely ignore this email.</td></tr>"
                // footer
                + "<tr><td align=\"center\" style=\"background:#faf5ef;border-top:1px solid #efe3d4;padding:18px 20px;font-size:12px;line-height:1.6;color:#9a8a7a;\">"
                + "Panchayat Team<br><a href=\"" + siteUrl + "\" style=\"color:#c0392b;text-decoration:none;\">" + siteUrl.replaceFirst("^https?://", "") + "</a></td></tr>"
                + "</table></td></tr></table></body></html>";
    }
}
