package com.panchayat.backend.wallet.service;

import com.panchayat.backend.admin.media.PublicImageStorage;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Red packet and coin pack pictures. They are kept on this server (they used to go to Cloudinary). */
@Service
public class WalletImageStorageService {

    private static final long MAX_IMAGE_SIZE = 5L * 1024 * 1024;

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp"
    );

    private final PublicImageStorage store;

    public WalletImageStorageService(PublicImageStorage store) {
        this.store = store;
    }

    public Map<String, String> uploadRedPacketImage(MultipartFile file) {
        return uploadImage(file, "wallet/redpackets");
    }

    public Map<String, String> uploadCoinPackImage(MultipartFile file) {
        return uploadImage(file, "wallet/coinpacks");
    }

    private Map<String, String> uploadImage(MultipartFile file, String folder) {
        validateImage(file);
        Map<String, Object> saved = store.uploadImage(file, folder);
        return Map.of(
                "imageUrl", saved.get("secure_url").toString(),
                "imagePublicId", saved.get("public_id").toString()
        );
    }

    /** Removes a picture kept on this server. Older Cloudinary ids are left alone. */
    public void deleteImage(String imagePublicId) {
        if (imagePublicId == null || imagePublicId.isBlank()) {
            return;
        }
        store.delete(imagePublicId);
    }

    private void validateImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Please select an image"
            );
        }

        if (file.getSize() > MAX_IMAGE_SIZE) {
            throw new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "Image size must not exceed 5 MB"
            );
        }

        String contentType = file.getContentType();

        if (contentType == null ||
                !ALLOWED_CONTENT_TYPES.contains(
                        contentType.toLowerCase(Locale.ROOT)
                )) {
            throw new ResponseStatusException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Only JPEG, PNG and WebP images are allowed"
            );
        }
    }
}
