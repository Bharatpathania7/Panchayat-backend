package com.panchayat.backend.vip.controller;

import com.panchayat.backend.admin.media.PublicImageStorage;
import com.panchayat.backend.config.CloudinaryService;
import com.panchayat.backend.vip.entity.VipCosmetic;
import com.panchayat.backend.vip.repository.VipCosmeticRepository;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/vip/cosmetics")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class VipCosmeticAdminController {

    private final CloudinaryService cloudinaryService;
    private final VipCosmeticRepository repository;
    private final PublicImageStorage store;

    public VipCosmeticAdminController(
            CloudinaryService cloudinaryService,
            VipCosmeticRepository repository,
            PublicImageStorage store
    ) {
        this.cloudinaryService = cloudinaryService;
        this.repository = repository;
        this.store = store;
    }

    @PostMapping(
            value = "/upload",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public VipCosmetic uploadCosmetic(
            @RequestParam("file") MultipartFile file,
            @RequestParam("vipLevel") Integer vipLevel,
            @RequestParam("type") VipCosmetic.CosmeticType type,
            @RequestParam("format") VipCosmetic.CosmeticFormat format
    ) {

        if (vipLevel == null || type == null || format == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "VIP level, type and format are required."
            );
        }

        Map<String, Object> uploadResult;

        try {
            uploadResult = cloudinaryService.uploadVipAsset(
                    file,
                    vipLevel,
                    type,
                    format
            );
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    ex.getMessage()
            );
        } catch (IOException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "The upload failed."
            );
        }

        VipCosmetic cosmetic = new VipCosmetic();
        cosmetic.setVipLevel(vipLevel);
        cosmetic.setCosmeticType(type);
        cosmetic.setFormat(format);
        cosmetic.setPublicId(uploadResult.get("public_id").toString());
        cosmetic.setSecureUrl(uploadResult.get("secure_url").toString());
        cosmetic.setOriginalFilename(file.getOriginalFilename());

        try {
            return repository.save(cosmetic);
        } catch (RuntimeException ex) {
            // The uploaded asset may need cleanup if database persistence fails.
            try {
                store.delete(uploadResult.get("public_id").toString());
            } catch (Exception cleanupException) {
                ex.addSuppressed(cleanupException);
            }

            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not save VIP cosmetic metadata."
            );
        }
    }

    @GetMapping
    public List<VipCosmetic> getAllCosmetics() {
        return repository.findAllByOrderByVipLevelAscCosmeticTypeAsc();
    }
}