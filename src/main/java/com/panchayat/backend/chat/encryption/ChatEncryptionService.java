
        package com.panchayat.backend.chat.encryption;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class ChatEncryptionService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int KEY_SIZE_BYTES = 32;
    private static final int NONCE_SIZE_BYTES = 12;
    private static final int TAG_SIZE_BITS = 128;

    private final SecretKeySpec secretKey;
    private final SecureRandom secureRandom = new SecureRandom();
    private final int keyVersion;

    public ChatEncryptionService(
            @Value("${app.chat.encryption-key-base64}")
            String base64Key,
            @Value("${app.chat.encryption-key-version:1}")
            int keyVersion
    ) {
        byte[] keyBytes;

        try {
            keyBytes = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "Chat encryption key must be valid Base64.", ex
            );
        }

        if (keyBytes.length != KEY_SIZE_BYTES) {
            throw new IllegalArgumentException(
                    "Chat encryption key must decode to exactly 32 bytes."
            );
        }

        if (keyVersion < 1) {
            throw new IllegalArgumentException(
                    "Chat encryption key version must be positive."
            );
        }

        this.secretKey = new SecretKeySpec(keyBytes, "AES");
        this.keyVersion = keyVersion;
    }

    public EncryptedPayload encrypt(String plaintext) {
        if (plaintext == null) {
            throw new IllegalArgumentException("Plaintext cannot be null.");
        }

        byte[] nonce = new byte[NONCE_SIZE_BYTES];
        secureRandom.nextBytes(nonce);

        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);

            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    secretKey,
                    new GCMParameterSpec(TAG_SIZE_BITS, nonce)
            );

            byte[] ciphertext = cipher.doFinal(
                    plaintext.getBytes(StandardCharsets.UTF_8)
            );

            return new EncryptedPayload(
                    Base64.getEncoder().encodeToString(ciphertext),
                    Base64.getEncoder().encodeToString(nonce),
                    keyVersion
            );

        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(
                    "Unable to encrypt chat message.", ex
            );
        }
    }

    public String decrypt(
            String ciphertextBase64,
            String nonceBase64,
            int messageKeyVersion
    ) {
        if (ciphertextBase64 == null || nonceBase64 == null) {
            throw new IllegalArgumentException(
                    "Ciphertext and nonce are required."
            );
        }

        if (messageKeyVersion != keyVersion) {
            throw new IllegalArgumentException(
                    "Unsupported encryption key version: "
                            + messageKeyVersion
            );
        }

        try {
            byte[] ciphertext =
                    Base64.getDecoder().decode(ciphertextBase64);
            byte[] nonce =
                    Base64.getDecoder().decode(nonceBase64);

            if (nonce.length != NONCE_SIZE_BYTES) {
                throw new IllegalArgumentException(
                        "Invalid AES-GCM nonce length."
                );
            }

            Cipher cipher = Cipher.getInstance(ALGORITHM);

            cipher.init(
                    Cipher.DECRYPT_MODE,
                    secretKey,
                    new GCMParameterSpec(TAG_SIZE_BITS, nonce)
            );

            byte[] plaintext = cipher.doFinal(ciphertext);

            return new String(plaintext, StandardCharsets.UTF_8);

        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "Unable to decrypt chat message.", ex
            );
        }
    }

    public int getKeyVersion() {
        return keyVersion;
    }

    public record EncryptedPayload(
            String ciphertext,
            String nonce,
            int keyVersion
    ) {
    }

    public byte[] encryptBytes(byte[] plaintext) {
        if (plaintext == null || plaintext.length == 0) {
            throw new IllegalArgumentException(
                    "Data to encrypt cannot be empty."
            );
        }

        byte[] nonce = new byte[NONCE_SIZE_BYTES];
        secureRandom.nextBytes(nonce);

        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);

            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    secretKey,
                    new GCMParameterSpec(TAG_SIZE_BITS, nonce)
            );

            byte[] ciphertext = cipher.doFinal(plaintext);

            // Store nonce + ciphertext together.
            byte[] result = new byte[nonce.length + ciphertext.length];

            System.arraycopy(
                    nonce, 0,
                    result, 0,
                    nonce.length
            );

            System.arraycopy(
                    ciphertext, 0,
                    result, nonce.length,
                    ciphertext.length
            );

            return result;

        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(
                    "Unable to encrypt binary data.", ex
            );
        }
    }

    public byte[] decryptBytes(byte[] encryptedData) {
        if (encryptedData == null
                || encryptedData.length < NONCE_SIZE_BYTES + 16) {
            throw new IllegalArgumentException(
                    "Invalid encrypted data."
            );
        }

        byte[] nonce = java.util.Arrays.copyOfRange(
                encryptedData, 0, NONCE_SIZE_BYTES
        );

        byte[] ciphertext = java.util.Arrays.copyOfRange(
                encryptedData, NONCE_SIZE_BYTES, encryptedData.length
        );

        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);

            cipher.init(
                    Cipher.DECRYPT_MODE,
                    secretKey,
                    new GCMParameterSpec(TAG_SIZE_BITS, nonce)
            );

            return cipher.doFinal(ciphertext);

        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(
                    "Unable to decrypt binary data.", ex
            );
        }
    }

}
