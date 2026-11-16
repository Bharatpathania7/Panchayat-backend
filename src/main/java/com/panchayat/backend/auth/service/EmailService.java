package com.panchayat.backend.auth.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class EmailService {

    private final JavaMailSender mailSender;
    private final String fromEmail;

    public EmailService(
            JavaMailSender mailSender,
            @Value("${spring.mail.username}") String username,
            @Value("${app.mail.from:}") String from,
            @Value("${app.mail.from-name:Panchayat}") String fromName
    ) {
        this.mailSender = mailSender;
        // A domain sender (MAIL_FROM) when set, else the login address (Gmail). Shown as "Panchayat <no-reply@...>".
        String address = from == null || from.isBlank() ? username : from.strip();
        this.fromEmail = fromName == null || fromName.isBlank() ? address : fromName.strip() + " <" + address + ">";
    }

    public void sendOtp(String recipientEmail, String otp) {
        SimpleMailMessage message = new SimpleMailMessage();

        message.setFrom(fromEmail);
        message.setTo(recipientEmail);
        message.setSubject("Your Panchayat verification code");

        message.setText(
                "Hello,\n\n"
                        + "Your Panchayat verification code is: " + otp + "\n\n"
                        + "This code expires in 2 minutes.\n"
                        + "If you did not request this code, please ignore this email.\n\n"
                        + "Regards,\nPanchayat Team"
        );

        mailSender.send(message);
    }
}