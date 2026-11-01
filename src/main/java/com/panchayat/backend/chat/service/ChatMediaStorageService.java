
        package com.panchayat.backend.chat.service;

import com.panchayat.backend.chat.encryption.ChatMediaEncryptionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

@Service
public class ChatMediaStorageService {

    private final ChatMediaEncryptionService encryptionService;
    private final Path storageDirectory;
    private final long maxImageSizeBytes;

    public ChatMediaStorageService(
            ChatMediaEncryptionService encryptionService,
            @Value("${app.chat.media-storage-path}")
            String storagePath,
            @Value("${app.chat.max-image-size-bytes:5242880}")
            long maxImageSizeBytes
    ) {
        this.encryptionService = encryptionService;
        this.storageDirectory = Paths.get(storagePath)
                .toAbsolutePath()
                .normalize();
        this.maxImageSizeBytes = maxImageSizeBytes;

        if (maxImageSizeBytes <= 0) {
            throw new IllegalArgumentException(
                    "Maximum image size must be positive."
            );
        }

        try {
            Files.createDirectories(storageDirectory);
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Unable to initialize private chat storage.", ex
            );
        }
    }

    public StoredMedia storeImage(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new IllegalArgumentException("Image cannot be empty.");
        }

        if (imageBytes.length > maxImageSizeBytes) {
            throw new IllegalArgumentException(
                    "Image exceeds the maximum allowed size."
            );
        }

        ChatMediaEncryptionService.EncryptedMedia encrypted =
                encryptionService.encrypt(imageBytes);

        String storageKey = UUID.randomUUID().toString();
        Path target = resolveStoragePath(storageKey);

        byte[] ciphertext = encrypted.ciphertext();
        byte[] nonce = encrypted.nonce();

        // File format: 12-byte nonce followed by AES-GCM ciphertext + tag.
        byte[] storedBytes = new byte[nonce.length + ciphertext.length];

        System.arraycopy(
                nonce, 0, storedBytes, 0, nonce.length
        );

        System.arraycopy(
                ciphertext, 0, storedBytes, nonce.length, ciphertext.length
        );

        try {
            Files.write(
                    target,
                    storedBytes,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
            );

            return new StoredMedia(
                    storageKey,
                    encrypted.keyVersion(),
                    imageBytes.length
            );

        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Unable to store encrypted chat media.", ex
            );
        }
    }

    public byte[] loadImage(String storageKey, int keyVersion) {
        Path file = resolveStoragePath(storageKey);

        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("Image not found.");
        }

        try {
            long storedSize = Files.size(file);

            // Maximum plaintext size plus nonce and GCM authentication tag.
            long maximumStoredSize = maxImageSizeBytes + 12 + 16;

            if (storedSize <= 28 || storedSize > maximumStoredSize) {
                throw new IllegalStateException(
                        "Stored media has an invalid size."
                );
            }

            byte[] storedBytes = Files.readAllBytes(file);

            byte[] nonce = java.util.Arrays.copyOfRange(
                    storedBytes, 0, 12
            );

            byte[] ciphertext = java.util.Arrays.copyOfRange(
                    storedBytes, 12, storedBytes.length
            );

            return encryptionService.decrypt(
                    ciphertext,
                    nonce,
                    keyVersion
            );

        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Unable to read encrypted chat media.", ex
            );
        }
    }

    public void deleteImage(String storageKey) {
        Path file = resolveStoragePath(storageKey);

        try {
            Files.deleteIfExists(file);
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Unable to delete chat media.", ex
            );
        }
    }

    private Path resolveStoragePath(String storageKey) {
        if (storageKey == null
                || !storageKey.matches(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-"
                        + "[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                        + "[0-9a-fA-F]{12}"
        )) {
            throw new IllegalArgumentException("Invalid media storage key.");
        }

        Path target = storageDirectory
                .resolve(storageKey + ".enc")
                .normalize();

        if (!target.startsWith(storageDirectory)) {
            throw new IllegalArgumentException("Invalid storage path.");
        }

        return target;
    }

    public record StoredMedia(
            String storageKey,
            int encryptionKeyVersion,
            long originalSizeBytes
    ) {
    }
}
