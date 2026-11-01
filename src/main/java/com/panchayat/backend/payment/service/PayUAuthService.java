package com.panchayat.backend.payment.service;

import com.panchayat.backend.payment.config.PayUProperties;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

@Service
public class PayUAuthService {

    private final PayUProperties properties;

    public PayUAuthService(PayUProperties properties) {
        this.properties = properties;
    }

    public String generateDateHeader() {
        return ZonedDateTime.now(ZoneOffset.UTC)
                .format(DateTimeFormatter.RFC_1123_DATE_TIME);
    }

    public String generateAuthorization(String requestBody, String date) {
        String merchantKey = properties.getMerchantKey();
        String merchantSalt = properties.getMerchantSalt();

        if (merchantKey == null || merchantKey.isBlank()
                || merchantSalt == null || merchantSalt.isBlank()) {
            throw new IllegalStateException(
                    "PayU merchant credentials are not configured"
            );
        }

        String input = requestBody + "|" + date + "|" + merchantSalt;

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");

            String signature = HexFormat.of().formatHex(
                    digest.digest(input.getBytes(StandardCharsets.UTF_8))
            );

            return "hmac username=\"" + merchantKey
                    + "\", algorithm=\"sha512\", headers=\"date\", signature=\""
                    + signature + "\"";

        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(
                    "SHA-512 is unavailable", e
            );
        }
    }
}