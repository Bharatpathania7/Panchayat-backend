
        package com.panchayat.backend.wallet.service;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class WalletImageStorageService {

    private static final long MAX_IMAGE_SIZE = 5L * 1024 * 1024;

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp"
    );

    private final Cloudinary cloudinary;

    public WalletImageStorageService(Cloudinary cloudinary) {
        this.cloudinary = cloudinary;
    }

    public Map<String, String> uploadRedPacketImage(MultipartFile file) {
        return uploadImage(file, "panchayat/wallet/red-packets");
    }

    public Map<String, String> uploadCoinPackImage(MultipartFile file) {
        return uploadImage(file, "panchayat/wallet/coin-packs");
    }

    private Map<String, String> uploadImage(
            MultipartFile file,
            String folder
    ) {
        validateImage(file);

        try {
            Map<?, ?> result = cloudinary.uploader().upload(
                    file.getBytes(),
                    ObjectUtils.asMap(
                            "folder", folder,
                            "resource_type", "image",
                            "overwrite", false
                    )
            );

            Object secureUrl = result.get("secure_url");
            Object publicId = result.get("public_id");

            if (secureUrl == null || publicId == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "Cloudinary did not return image details"
                );
            }

            return Map.of(
                    "imageUrl", secureUrl.toString(),
                    "imagePublicId", publicId.toString()
            );

        } catch (IOException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Unable to upload image to Cloudinary",
                    exception
            );
        }
    }

    public void deleteImage(String imagePublicId) {
        if (imagePublicId == null || imagePublicId.isBlank()) {
            return;
        }

        // Only allow deletion from the wallet image folders.
        if (!imagePublicId.startsWith("panchayat/wallet/coin-packs/")
                && !imagePublicId.startsWith("panchayat/wallet/red-packets/")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid wallet image public ID"
            );
        }

        try {
            Map<?, ?> result = cloudinary.uploader().destroy(
                    imagePublicId,
                    ObjectUtils.asMap("resource_type", "image")
            );

            Object status = result.get("result");

            if (status != null
                    && !"ok".equals(status.toString())
                    && !"not found".equals(status.toString())) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "Unable to delete image from Cloudinary"
                );
            }

        } catch (IOException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Unable to delete image from Cloudinary",
                    exception
            );
        }
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
                        contentType.toLowerCase(Locale.ROOT))) {
            throw new ResponseStatusException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Only JPEG, PNG and WebP images are allowed"
            );
        }

        try {
            byte[] header = file.getInputStream().readNBytes(12);

            if (!isSupportedImageSignature(header)) {
                throw new ResponseStatusException(
                        HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                        "Uploaded file is not a valid supported image"
                );
            }

        } catch (IOException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Unable to read image",
                    exception
            );
        }
    }

    private boolean isSupportedImageSignature(byte[] bytes) {
        boolean jpeg = bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xFF
                && (bytes[1] & 0xFF) == 0xD8
                && (bytes[2] & 0xFF) == 0xFF;

        boolean png = bytes.length >= 8
                && (bytes[0] & 0xFF) == 0x89
                && bytes[1] == 0x50
                && bytes[2] == 0x4E
                && bytes[3] == 0x47
                && bytes[4] == 0x0D
                && bytes[5] == 0x0A
                && bytes[6] == 0x1A
                && bytes[7] == 0x0A;

        boolean webp = bytes.length >= 12
                && bytes[0] == 'R'
                && bytes[1] == 'I'
                && bytes[2] == 'F'
                && bytes[3] == 'F'
                && bytes[8] == 'W'
                && bytes[9] == 'E'
                && bytes[10] == 'B'
                && bytes[11] == 'P';

        return jpeg || png || webp;
    }
}
