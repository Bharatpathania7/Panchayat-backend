
        package com.panchayat.backend.user.service;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import com.panchayat.backend.auth.entity.User;
import com.panchayat.backend.auth.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class UserMediaStorageService {

    private static final long MAX_AVATAR_SIZE = 5L * 1024 * 1024;
    private static final long MAX_BANNER_SIZE = 8L * 1024 * 1024;

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp"
    );

    private final Cloudinary cloudinary;
    private final UserRepository userRepository;

    public UserMediaStorageService(
            Cloudinary cloudinary,
            UserRepository userRepository
    ) {
        this.cloudinary = cloudinary;
        this.userRepository = userRepository;
    }

    /**
     * Upload or replace the authenticated user's avatar.
     */
    @Transactional
    public Map<String, Object> uploadAvatar(
            String authenticatedEmail,
            MultipartFile file
    ) {
        validateImage(file, MAX_AVATAR_SIZE);

        User user = findUser(authenticatedEmail);

        String oldPublicId = user.getAvatarPublicId();

        Map<String, Object> uploaded = uploadToCloudinary(
                file,
                "panchayat/users/" + user.getId() + "/avatar"
        );

        String newUrl = (String) uploaded.get("secure_url");
        String newPublicId = (String) uploaded.get("public_id");

        if (newUrl == null || newPublicId == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Cloudinary did not return the uploaded image details"
            );
        }

        user.setAvatarUrl(newUrl);
        user.setAvatarPublicId(newPublicId);

        userRepository.saveAndFlush(user);

        // Delete the previous image only after the new image is saved.
        deleteOldImageIfDifferent(oldPublicId, newPublicId);

        return Map.of(
                "success", true,
                "message", "Avatar uploaded successfully",
                "avatarUrl", newUrl,
                "avatarPublicId", newPublicId
        );
    }

    /**
     * Upload or replace the authenticated user's banner.
     */
    @Transactional
    public Map<String, Object> uploadBanner(
            String authenticatedEmail,
            MultipartFile file
    ) {
        validateImage(file, MAX_BANNER_SIZE);

        User user = findUser(authenticatedEmail);

        String oldPublicId = user.getBannerPublicId();

        Map<String, Object> uploaded = uploadToCloudinary(
                file,
                "panchayat/users/" + user.getId() + "/banner"
        );

        String newUrl = (String) uploaded.get("secure_url");
        String newPublicId = (String) uploaded.get("public_id");

        if (newUrl == null || newPublicId == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Cloudinary did not return the uploaded image details"
            );
        }

        user.setBannerUrl(newUrl);
        user.setBannerPublicId(newPublicId);

        userRepository.saveAndFlush(user);

        // Delete the previous image only after the new image is saved.
        deleteOldImageIfDifferent(oldPublicId, newPublicId);

        return Map.of(
                "success", true,
                "message", "Banner uploaded successfully",
                "bannerUrl", newUrl,
                "bannerPublicId", newPublicId
        );
    }

    /**
     * Remove the authenticated user's avatar.
     */
    @Transactional
    public Map<String, Object> deleteAvatar(String authenticatedEmail) {
        User user = findUser(authenticatedEmail);

        String oldPublicId = user.getAvatarPublicId();

        user.setAvatarUrl(null);
        user.setAvatarPublicId(null);

        userRepository.saveAndFlush(user);

        deleteOldImageIfDifferent(oldPublicId, null);

        return Map.of(
                "success", true,
                "message", "Avatar removed successfully"
        );
    }

    /**
     * Remove the authenticated user's banner.
     */
    @Transactional
    public Map<String, Object> deleteBanner(String authenticatedEmail) {
        User user = findUser(authenticatedEmail);

        String oldPublicId = user.getBannerPublicId();

        user.setBannerUrl(null);
        user.setBannerPublicId(null);

        userRepository.saveAndFlush(user);

        deleteOldImageIfDifferent(oldPublicId, null);

        return Map.of(
                "success", true,
                "message", "Banner removed successfully"
        );
    }

    private User findUser(String authenticatedEmail) {
        if (authenticatedEmail == null || authenticatedEmail.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Authentication is required"
            );
        }

        return userRepository.findByEmailIgnoreCase(authenticatedEmail)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "User not found"
                ));
    }

    private void validateImage(MultipartFile file, long maxSize) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Please select an image to upload"
            );
        }

        if (file.getSize() > maxSize) {
            throw new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "Image size exceeds the allowed limit of "
                            + (maxSize / (1024 * 1024)) + " MB"
            );
        }

        String contentType = file.getContentType();

        if (contentType == null
                || !ALLOWED_CONTENT_TYPES.contains(
                contentType.toLowerCase(Locale.ROOT)
        )) {
            throw new ResponseStatusException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Only JPEG, PNG and WebP images are allowed"
            );
        }

        // Check file signature rather than trusting the content type alone.
        try {
            byte[] header = file.getInputStream().readNBytes(12);

            if (!isSupportedImageSignature(header)) {
                throw new ResponseStatusException(
                        HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                        "The uploaded file is not a supported image"
                );
            }
        } catch (IOException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Unable to read the uploaded image",
                    exception
            );
        }
    }

    private boolean isSupportedImageSignature(byte[] bytes) {
        // JPEG: FF D8 FF
        boolean jpeg = bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xFF
                && (bytes[1] & 0xFF) == 0xD8
                && (bytes[2] & 0xFF) == 0xFF;

        // PNG: 89 50 4E 47 0D 0A 1A 0A
        boolean png = bytes.length >= 8
                && (bytes[0] & 0xFF) == 0x89
                && bytes[1] == 0x50
                && bytes[2] == 0x4E
                && bytes[3] == 0x47
                && bytes[4] == 0x0D
                && bytes[5] == 0x0A
                && bytes[6] == 0x1A
                && bytes[7] == 0x0A;

        // WebP: RIFF....WEBP
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

    private Map<String, Object> uploadToCloudinary(
            MultipartFile file,
            String folder
    ) {
        try {
            return cloudinary.uploader().upload(
                    file.getBytes(),
                    ObjectUtils.asMap(
                            "folder", folder,
                            "resource_type", "image",
                            "overwrite", false
                    )
            );
        } catch (IOException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Unable to upload image to Cloudinary",
                    exception
            );
        }
    }

    private void deleteOldImageIfDifferent(
            String oldPublicId,
            String newPublicId
    ) {
        if (oldPublicId == null
                || oldPublicId.isBlank()
                || oldPublicId.equals(newPublicId)) {
            return;
        }

        try {
            cloudinary.uploader().destroy(
                    oldPublicId,
                    ObjectUtils.asMap("resource_type", "image")
            );
        } catch (Exception exception) {
            // The database already contains the new state.
            // Log this failure and retry cleanup operationally.
            System.err.println(
                    "Failed to delete old Cloudinary image: "
                            + oldPublicId
            );
        }
    }
}
