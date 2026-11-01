
        package com.panchayat.backend.chat.encryption;

import org.springframework.stereotype.Service;

import java.util.Arrays;

@Service
public class ChatMediaEncryptionService {

    private final ChatEncryptionService encryptionService;

    public ChatMediaEncryptionService(
            ChatEncryptionService encryptionService
    ) {
        this.encryptionService = encryptionService;
    }

    public EncryptedMedia encrypt(byte[] plaintext) {
        byte[] encryptedData = encryptionService.encryptBytes(plaintext);

        // First 12 bytes contain the nonce.
        int nonceLength = 12;

        if (encryptedData.length <= nonceLength) {
            throw new IllegalStateException(
                    "Invalid encrypted media output."
            );
        }

        byte[] nonce = Arrays.copyOfRange(
                encryptedData, 0, nonceLength
        );

        byte[] ciphertext = Arrays.copyOfRange(
                encryptedData, nonceLength, encryptedData.length
        );

        return new EncryptedMedia(
                ciphertext,
                nonce,
                encryptionService.getKeyVersion()
        );
    }

    public byte[] decrypt(
            byte[] ciphertext,
            byte[] nonce,
            int keyVersion
    ) {
        if (ciphertext == null || nonce == null || nonce.length != 12) {
            throw new IllegalArgumentException(
                    "Invalid encrypted media payload."
            );
        }

        if (keyVersion != encryptionService.getKeyVersion()) {
            throw new IllegalArgumentException(
                    "Unsupported encryption key version: " + keyVersion
            );
        }

        byte[] encryptedData = new byte[nonce.length + ciphertext.length];

        System.arraycopy(
                nonce, 0,
                encryptedData, 0,
                nonce.length
        );

        System.arraycopy(
                ciphertext, 0,
                encryptedData, nonce.length,
                ciphertext.length
        );

        return encryptionService.decryptBytes(encryptedData);
    }

    public int getKeyVersion() {
        return encryptionService.getKeyVersion();
    }

    public record EncryptedMedia(
            byte[] ciphertext,
            byte[] nonce,
            int keyVersion
    ) {
        public EncryptedMedia {
            ciphertext = ciphertext.clone();
            nonce = nonce.clone();
        }

        @Override
        public byte[] ciphertext() {
            return ciphertext.clone();
        }

        @Override
        public byte[] nonce() {
            return nonce.clone();
        }
    }
}
