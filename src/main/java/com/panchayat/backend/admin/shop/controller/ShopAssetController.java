
        package com.panchayat.backend.admin.shop.controller;

import com.panchayat.backend.admin.shop.service.ShopAssetService;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/shop/assets")
public class ShopAssetController {

    private final ShopAssetService shopAssetService;

    public ShopAssetController(ShopAssetService shopAssetService) {
        this.shopAssetService = shopAssetService;
    }

    @PostMapping(
            value = "/upload",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<Map<String, Object>> uploadAsset(
            @RequestParam("file") MultipartFile file
    ) {

        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Please select a file to upload"
            );
        }

        String filename = StringUtils.cleanPath(
                file.getOriginalFilename() == null
                        ? ""
                        : file.getOriginalFilename()
        );

        String extension = StringUtils.getFilenameExtension(filename);

        if (extension == null || extension.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "File extension is missing"
            );
        }

        extension = extension.toLowerCase(Locale.ROOT);

        // Upload asset through the existing Cloudinary service.
        String assetUrl = shopAssetService.upload(file);

        String assetType = "svga".equals(extension)
                ? "SVGA"
                : "IMAGE";

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Asset uploaded successfully",
                "assetPath", assetUrl,
                "assetUrl", assetUrl,
                "assetType", assetType
        ));
    }
}
