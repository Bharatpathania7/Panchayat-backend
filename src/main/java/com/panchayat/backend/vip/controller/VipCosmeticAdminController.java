package com.panchayat.backend.vip.controller;

import com.panchayat.backend.vip.entity.VipCosmetic;
import com.panchayat.backend.vip.repository.VipCosmeticRepository;
import com.panchayat.backend.vip.service.VipAssetService;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * VIP cosmetics (one file per VIP level + type + format), stored on this server. Uploading a slot that already has a
 * file REPLACES it (same row, new file; the old file is removed). PUT /{id}/file replaces one by id, DELETE /{id} removes it.
 */
@RestController
@RequestMapping("/api/v1/admin/vip/cosmetics")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class VipCosmeticAdminController {

    private final VipAssetService assets;
    private final VipCosmeticRepository repository;

    public VipCosmeticAdminController(VipAssetService assets, VipCosmeticRepository repository) {
        this.assets = assets;
        this.repository = repository;
    }

    /** Upload a VIP cosmetic; if this level + type + format already has one, its file is replaced. */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public VipCosmetic uploadCosmetic(
            @RequestParam("file") MultipartFile file,
            @RequestParam("vipLevel") Integer vipLevel,
            @RequestParam("type") VipCosmetic.CosmeticType type,
            @RequestParam("format") VipCosmetic.CosmeticFormat format
    ) {
        if (vipLevel == null || type == null || format == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "VIP level, type and format are required.");
        }
        Optional<VipCosmetic> existing = repository.findByVipLevelAndCosmeticTypeAndFormat(vipLevel, type, format);
        return saveFile(existing.orElseGet(VipCosmetic::new), file, vipLevel, type, format);
    }

    /** Replace the file of one VIP cosmetic (its level, type and format stay the same). */
    @RequestMapping(value = "/{id:[0-9]+}/file", method = {RequestMethod.PUT, RequestMethod.POST}, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public VipCosmetic replaceFile(@PathVariable Long id, @RequestParam("file") MultipartFile file) {
        VipCosmetic c = repository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "That VIP cosmetic doesn't exist."));
        return saveFile(c, file, c.getVipLevel(), c.getCosmeticType(), c.getFormat());
    }

    /** Remove a VIP cosmetic and its file. */
    @DeleteMapping("/{id:[0-9]+}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        VipCosmetic c = repository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "That VIP cosmetic doesn't exist."));
        String old = c.getPublicId();
        repository.delete(c);
        repository.flush();
        assets.delete(old);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public List<VipCosmetic> getAllCosmetics() {
        return repository.findAllByOrderByVipLevelAscCosmeticTypeAsc();
    }

    private VipCosmetic saveFile(VipCosmetic c, MultipartFile file, int vipLevel, VipCosmetic.CosmeticType type, VipCosmetic.CosmeticFormat format) {
        Map<String, Object> up;
        try {
            up = assets.save(file, vipLevel, type, format);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
        String oldFile = c.getPublicId();
        c.setVipLevel(vipLevel);
        c.setCosmeticType(type);
        c.setFormat(format);
        c.setPublicId(up.get("public_id").toString());
        c.setSecureUrl(up.get("secure_url").toString());
        c.setOriginalFilename(file.getOriginalFilename());
        VipCosmetic saved;
        try {
            saved = repository.saveAndFlush(c);
        } catch (RuntimeException ex) {
            assets.delete(up.get("public_id").toString());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not save the VIP cosmetic.");
        }
        // The new file is in: the replaced one can go
        if (oldFile != null && !oldFile.equals(saved.getPublicId())) assets.delete(oldFile);
        return saved;
    }

    /** Readable { "message": ... } errors for the admin panel. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> readable(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("message", e.getReason() == null ? "That didn't work" : e.getReason()));
    }
}
