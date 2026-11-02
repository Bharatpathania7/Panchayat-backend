
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
import java.util.Locale;

        @Service
public class PayUAuthService {

    private final PayUProperties properties;

    public PayUAuthService(PayUProperties properties) {
        this.properties = properties;
    }

    /**
     * Generates the HTTP Date header in UTC.
     */
    public String generateDateHeader() {
        return ZonedDateTime.now(ZoneOffset.UTC)
                .format(
                        DateTimeFormatter.ofPattern(
                                "EEE, dd MMM yyyy HH:mm:ss 'GMT'",
                                Locale.US
                        )
                );
    }

    /**
     * Generates the PayU v2 HMAC-style Authorization header.
     *
     * Formula:
     * SHA-512(requestBody + "|" + date + "|" + merchantSalt)
     *
     * Never log the merchant salt, signature, or Authorization header.
     */
    public String generateAuthorization(
            String requestBody,
            String date) {

        if (requestBody == null || requestBody.isBlank()) {
            throw new IllegalArgumentException(
                    "PayU request body is required"
            );
        }

        if (date == null || date.isBlank()) {
            throw new IllegalArgumentException(
                    "PayU Date header is required"
            );
        }

        String merchantKey = properties.getMerchantKey();
        String merchantSalt = properties.getMerchantSalt();

        if (merchantKey == null || merchantKey.isBlank()
                || merchantSalt == null || merchantSalt.isBlank()) {

            throw new IllegalStateException(
                    "PayU merchant credentials are not configured"
            );
        }

        if (!merchantKey.equals(merchantKey.trim())
                || !merchantSalt.equals(merchantSalt.trim())) {

            throw new IllegalStateException(
                    "PayU credentials contain leading or trailing whitespace"
            );
        }

        String input = requestBody + "|" + date + "|" + merchantSalt;

        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-512");

            String signature = HexFormat.of().formatHex(
                    digest.digest(
                            input.getBytes(StandardCharsets.UTF_8)
                    )
            );

            return "hmac username=\"" + merchantKey
                    + "\", algorithm=\"sha512\", headers=\"date\", signature=\""
                    + signature + "\"";

        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-512 is unavailable",
                    exception
            );
        }
    }
}
