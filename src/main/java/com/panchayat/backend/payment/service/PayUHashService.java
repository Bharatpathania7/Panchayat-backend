package com.panchayat.backend.payment.service;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Service
public class PayUHashService {

    public String generateSha512(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            byte[] hashBytes = digest.digest(
                    input.getBytes(StandardCharsets.UTF_8)
            );

            return HexFormat.of().formatHex(hashBytes);

        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(
                    "SHA-512 is not available", e
            );
        }
    }

    public boolean matches(String expectedHash, String receivedHash) {
        if (expectedHash == null || receivedHash == null) {
            return false;
        }

        byte[] expected = expectedHash.trim()
                .toLowerCase()
                .getBytes(StandardCharsets.US_ASCII);

        byte[] received = receivedHash.trim()
                .toLowerCase()
                .getBytes(StandardCharsets.US_ASCII);

        return MessageDigest.isEqual(expected, received);
    }
}