package com.panchayat.backend.vip.service;

import com.panchayat.backend.admin.media.PublicImageStorage;
import com.panchayat.backend.vip.entity.VipCosmetic;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.Locale;
import java.util.Map;

/**
 * Stores VIP cosmetic files (frame, bubble, entry, badge) on this server, under uploads/public/vip/vip{level}.
 * Replaced files are removed from the server once the new one is saved. (Cloudinary is not used any more.)
 */
@Service
public class VipAssetService {

    private static final long MAX_FILE_SIZE = 15L * 1024 * 1024;

    private final PublicImageStorage store;

    public VipAssetService(PublicImageStorage store) {
        this.store = store;
    }

    /** Checks the file and saves it. Returns secure_url (the public link) and public_id (the server path). */
    public Map<String, Object> save(MultipartFile file, int vipLevel, VipCosmetic.CosmeticType type, VipCosmetic.CosmeticFormat format) {
        validate(file, vipLevel, type, format);
        Map<String, Object> result = store.uploadAsset(file, "vip/vip" + vipLevel);
        if (result.get("secure_url") == null || result.get("public_id") == null) {
            throw new IllegalStateException("The server couldn't save the file.");
        }
        return result;
    }

    /** Removes a file that was on this server (old Cloudinary ids are simply ignored). */
    public void delete(String publicId) {
        try {
            store.delete(publicId);
        } catch (Exception ignored) {
            // a leftover file is harmless
        }
    }

    private static void validate(MultipartFile file, int vipLevel, VipCosmetic.CosmeticType type, VipCosmetic.CosmeticFormat format) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("Please select a file.");
        if (vipLevel < 1 || vipLevel > 11) throw new IllegalArgumentException("VIP level must be between 1 and 11.");
        if (file.getSize() > MAX_FILE_SIZE) throw new IllegalArgumentException("Maximum file size is 15 MB.");
        if (type == null || format == null) throw new IllegalArgumentException("Cosmetic type and format are required.");
        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) throw new IllegalArgumentException("Original filename is required.");
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) throw new IllegalArgumentException("File must have a valid extension.");
        String extension = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!extension.equals(format.name().toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("File extension does not match the selected format.");
        if (format == VipCosmetic.CosmeticFormat.PNG && type != VipCosmetic.CosmeticType.BADGE) {
            throw new IllegalArgumentException("PNG format is currently supported only for BADGE.");
        }
    }
}
