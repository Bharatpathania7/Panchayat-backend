package com.panchayat.backend.config;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import com.panchayat.backend.vip.entity.VipCosmetic;
import com.panchayat.backend.vip.repository.VipCosmeticRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;

@Service
public class CloudinaryService {

    private static final long MAX_FILE_SIZE = 15L * 1024 * 1024;

    private final Cloudinary cloudinary;
    private final VipCosmeticRepository vipCosmeticRepository;

    public CloudinaryService(
            Cloudinary cloudinary,
            VipCosmeticRepository vipCosmeticRepository
    ) {
        this.cloudinary = cloudinary;
        this.vipCosmeticRepository = vipCosmeticRepository;
    }

    public Map<?, ?> uploadVipAsset(
            MultipartFile file,
            int vipLevel,
            VipCosmetic.CosmeticType type,
            VipCosmetic.CosmeticFormat format
    ) throws IOException {

        validateFile(file, vipLevel, type, format);

        // Prevent replacing an existing asset for the same slot and format.
        boolean alreadyExists = vipCosmeticRepository
                .findByVipLevelAndCosmeticTypeAndFormat(
                        vipLevel,
                        type,
                        format
                )
                .isPresent();

        if (alreadyExists) {
            throw new IllegalArgumentException(
                    "This VIP cosmetic already exists. Delete it before uploading a replacement."
            );
        }

        String extension = format.name().toLowerCase(Locale.ROOT);

        String folder = "panchayat/vip/vip" + vipLevel;

        String publicId = type.name().toLowerCase(Locale.ROOT)
                + "_" + extension;

        String resourceType = format == VipCosmetic.CosmeticFormat.SVGA
                ? "raw"
                : "image";

        Map<?, ?> result = cloudinary.uploader().upload(
                file.getBytes(),
                ObjectUtils.asMap(
                        "resource_type", resourceType,
                        "folder", folder,
                        "public_id", publicId,
                        "overwrite", false,
                        "unique_filename", false,
                        "use_filename", false,
                        "invalidate", true
                )
        );

        if (result.get("secure_url") == null
                || result.get("public_id") == null) {
            throw new IOException(
                    "Cloudinary upload did not return the required asset information."
            );
        }

        return result;
    }

    private void validateFile(
            MultipartFile file,
            int vipLevel,
            VipCosmetic.CosmeticType type,
            VipCosmetic.CosmeticFormat format
    ) {

        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Please select a file.");
        }

        if (vipLevel < 1 || vipLevel > 7) {
            throw new IllegalArgumentException(
                    "VIP level must be between 1 and 7."
            );
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException(
                    "Maximum file size is 15 MB."
            );
        }

        if (type == null || format == null) {
            throw new IllegalArgumentException(
                    "Cosmetic type and format are required."
            );
        }

        String filename = file.getOriginalFilename();

        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException(
                    "Original filename is required."
            );
        }

        int dotIndex = filename.lastIndexOf('.');

        if (dotIndex < 0 || dotIndex == filename.length() - 1) {
            throw new IllegalArgumentException(
                    "File must have a valid extension."
            );
        }

        String extension = filename.substring(dotIndex + 1)
                .toLowerCase(Locale.ROOT);

        if (!extension.equals(format.name().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException(
                    "File extension does not match the selected format."
            );
        }

        if (format == VipCosmetic.CosmeticFormat.PNG
                && type != VipCosmetic.CosmeticType.BADGE) {
            throw new IllegalArgumentException(
                    "PNG format is currently supported only for BADGE."
            );
        }
    }
}