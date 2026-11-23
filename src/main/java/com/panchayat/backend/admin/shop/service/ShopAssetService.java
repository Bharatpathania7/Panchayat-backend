
        package com.panchayat.backend.admin.shop.service;

import com.panchayat.backend.admin.media.PublicImageStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ShopAssetService {

    private static final Logger log =
            LoggerFactory.getLogger(ShopAssetService.class);

    private static final long MAX_FILE_SIZE = 5L * 1024 * 1024;
    private static final long MAX_VIDEO_SIZE = 20L * 1024 * 1024;

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "webp", "svg", "svga", "mp4"
    );

    private final PublicImageStorage store;

    public ShopAssetService(PublicImageStorage store) {
        this.store = store;
    }

    public String upload(MultipartFile file) {

        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Please select a file to upload"
            );
        }

        String originalFilename = StringUtils.cleanPath(
                file.getOriginalFilename() == null
                        ? ""
                        : file.getOriginalFilename()
        );

        String extension = getExtension(originalFilename);

        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Only PNG, JPG, JPEG, WEBP, SVG, SVGA and MP4 files are allowed"
            );
        }

        long maxSize = "mp4".equals(extension) ? MAX_VIDEO_SIZE : MAX_FILE_SIZE;
        if (file.getSize() > maxSize) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Maximum file size is " + (maxSize / 1024 / 1024) + " MB"
            );
        }

        validateContentType(file, extension);

        if ("svg".equals(extension)) {
            // SVG files can carry scripts, so they aren't kept on our own address. Use PNG, JPG, WEBP or SVGA.
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "SVG files aren't supported. Export the picture as PNG, JPG or WEBP (or use SVGA for an animation)."
            );
        }

        // Kept on this server (it used to go to Cloudinary)
        Map<String, Object> saved = store.uploadAsset(file, "shop/products");
        return String.valueOf(saved.get("secure_url"));
    }

    private String getExtension(String filename) {

        int dotIndex = filename.lastIndexOf('.');

        if (dotIndex < 0 || dotIndex == filename.length() - 1) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "File extension is missing"
            );
        }

        return filename.substring(dotIndex + 1)
                .toLowerCase(Locale.ROOT);
    }

    private void validateContentType(
            MultipartFile file,
            String extension
    ) {
        String contentType = file.getContentType();

        if (contentType == null || contentType.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "File content type is missing"
            );
        }

        contentType = contentType.toLowerCase(Locale.ROOT);

        Set<String> allowedTypes = switch (extension) {
            case "png" -> Set.of("image/png");

            case "jpg", "jpeg" -> Set.of("image/jpeg");

            case "webp" -> Set.of("image/webp");

            case "svg" -> Set.of(
                    "image/svg+xml",
                    "text/xml",
                    "application/xml"
            );

            case "svga" -> Set.of(
                    "application/octet-stream",
                    "application/x-svga",
                    "application/vnd.svga",
                    "video/x-svga"
            );

            case "mp4" -> Set.of("video/mp4");

            default -> Set.of();
        };

        if (!allowedTypes.contains(contentType)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "File type does not match the allowed format. Received: "
                            + contentType
            );
        }
    }
}
