
        package com.panchayat.backend.admin.shop.service;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
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

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "webp", "svg", "svga"
    );

    private final Cloudinary cloudinary;

    public ShopAssetService(Cloudinary cloudinary) {
        this.cloudinary = cloudinary;
    }

    public String upload(MultipartFile file) {

        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Please select a file to upload"
            );
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Maximum file size is 5 MB"
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
                    "Only PNG, JPG, JPEG, WEBP, SVG and SVGA files are allowed"
            );
        }

        validateContentType(file, extension);

        boolean isSvga = "svga".equals(extension);

        try {
            String publicId = UUID.randomUUID().toString();

            Map<?, ?> result;

            if (isSvga) {
                // SVGA is an animation file, not a regular image.
                result = cloudinary.uploader().upload(
                        file.getBytes(),
                        ObjectUtils.asMap(
                                "resource_type", "raw",
                                "folder", "panchayat/shop-products",
                                "public_id", publicId + ".svga"
                        )
                );
            } else {
                result = cloudinary.uploader().upload(
                        file.getBytes(),
                        ObjectUtils.asMap(
                                "resource_type", "image",
                                "folder", "panchayat/shop-products",
                                "public_id", publicId
                        )
                );
            }

            Object secureUrl = result.get("secure_url");

            if (!(secureUrl instanceof String url)
                    || url.isBlank()
                    || !url.startsWith("https://")) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "Cloudinary did not return a valid HTTPS asset URL"
                );
            }

            return url;

        } catch (ResponseStatusException exception) {
            throw exception;

        } catch (IOException | RuntimeException exception) {
            log.error(
                    "Cloudinary upload failed for extension: {}",
                    extension,
                    exception
            );

            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Unable to upload asset to Cloudinary. Check backend logs."
            );
        }
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
