
        package com.panchayat.backend.vip.controller;

import com.panchayat.backend.vip.dto.VipCosmeticResponse;
import com.panchayat.backend.vip.entity.VipCosmetic;
import com.panchayat.backend.vip.repository.VipCosmeticRepository;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/vip/cosmetics")
public class VipCosmeticController {

    private final VipCosmeticRepository repository;

    public VipCosmeticController(VipCosmeticRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public ResponseEntity<List<VipCosmeticResponse>> getCosmetics(
            @RequestParam(required = false) Integer level,
            @RequestParam(required = false) VipCosmetic.CosmeticType type,
            @RequestParam(required = false) VipCosmetic.CosmeticFormat format
    ) {
        if (level != null && (level < 1 || level > 7)) {
            return ResponseEntity.badRequest().build();
        }

        List<VipCosmetic> cosmetics;

        if (level != null && type != null && format != null) {
            cosmetics = repository
                    .findAllByVipLevelAndCosmeticTypeAndFormat(
                            level, type, format
                    );
        } else if (level != null && type != null) {
            cosmetics = repository
                    .findAllByVipLevelAndCosmeticTypeOrderByCosmeticTypeAsc(
                            level, type
                    );

            if (format != null) {
                cosmetics = cosmetics.stream()
                        .filter(c -> c.getFormat() == format)
                        .toList();
            }
        } else if (level != null) {
            cosmetics = repository
                    .findAllByVipLevelOrderByCosmeticTypeAsc(level);

            if (format != null) {
                cosmetics = cosmetics.stream()
                        .filter(c -> c.getFormat() == format)
                        .toList();
            }
        } else if (type != null) {
            cosmetics = repository
                    .findAllByCosmeticTypeOrderByVipLevelAsc(type);

            if (format != null) {
                cosmetics = cosmetics.stream()
                        .filter(c -> c.getFormat() == format)
                        .toList();
            }
        } else if (format != null) {
            cosmetics = repository.findAllByFormatOrderByVipLevelAsc(format);
        } else {
            cosmetics = repository.findAllByOrderByVipLevelAscCosmeticTypeAsc();
        }

        List<VipCosmeticResponse> response = cosmetics.stream()
                .map(VipCosmeticResponse::from)
                .toList();

        return ResponseEntity.ok(response);
    }
}
