
        package com.panchayat.backend.user.controller;

import com.panchayat.backend.user.dto.PublicUserProfileResponse;
import com.panchayat.backend.user.dto.UpdateProfileRequest;
import com.panchayat.backend.user.dto.UserProfileResponse;
import com.panchayat.backend.user.dto.UserStatsResponse;
import com.panchayat.backend.user.service.UserMediaStorageService;
import com.panchayat.backend.user.service.UserProfileService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/users")
public class UserProfileController {

    private final UserProfileService userProfileService;
    private final UserMediaStorageService userMediaStorageService;

    public UserProfileController(
            UserProfileService userProfileService,
            UserMediaStorageService userMediaStorageService
    ) {
        this.userProfileService = userProfileService;
        this.userMediaStorageService = userMediaStorageService;
    }

    /**
     * Get the authenticated user's complete profile,
     * including VIP status and cosmetic URLs.
     */
    @GetMapping("/me")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<UserProfileResponse> getMyProfile(
            Authentication authentication
    ) {
        if (!isAuthenticated(authentication)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        UserProfileResponse response =
                userProfileService.getMyProfile(authentication.getName());

        return ResponseEntity.ok(response);
    }

    /**
     * Get another user's public profile,
     * including that user's VIP status and cosmetics.
     */
    @GetMapping("/{id}/profile")
    public ResponseEntity<PublicUserProfileResponse> getPublicProfile(
            @PathVariable Long id
    ) {
        PublicUserProfileResponse response =
                userProfileService.getPublicProfile(id);

        return ResponseEntity.ok(response);
    }

    /**
     * Update the authenticated user's profile.
     */
    @PatchMapping("/me")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<UserProfileResponse> updateMyProfile(
            Authentication authentication,
            @Valid @RequestBody UpdateProfileRequest request
    ) {
        if (!isAuthenticated(authentication)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        UserProfileResponse response =
                userProfileService.updateMyProfile(
                        authentication.getName(),
                        request
                );

        return ResponseEntity.ok(response);
    }

    /**
     * Get the authenticated user's game and experience statistics.
     */
    @GetMapping("/me/stats")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<UserStatsResponse> getMyStats(
            Authentication authentication
    ) {
        if (!isAuthenticated(authentication)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        UserStatsResponse response =
                userProfileService.getMyStats(authentication.getName());

        return ResponseEntity.ok(response);
    }

    /**
     * Upload or replace the authenticated user's avatar.
     */
    @PostMapping(
            value = "/me/avatar",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<Map<String, Object>> uploadAvatar(
            Authentication authentication,
            @RequestParam("file") MultipartFile file
    ) {
        if (!isAuthenticated(authentication)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Map<String, Object> response =
                userMediaStorageService.uploadAvatar(
                        authentication.getName(),
                        file
                );

        return ResponseEntity.ok(response);
    }

    /**
     * Upload or replace the authenticated user's banner.
     */
    @PostMapping(
            value = "/me/banner",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<Map<String, Object>> uploadBanner(
            Authentication authentication,
            @RequestParam("file") MultipartFile file
    ) {
        if (!isAuthenticated(authentication)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Map<String, Object> response =
                userMediaStorageService.uploadBanner(
                        authentication.getName(),
                        file
                );

        return ResponseEntity.ok(response);
    }

    /**
     * Remove the authenticated user's avatar.
     */
    @DeleteMapping("/me/avatar")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<Map<String, Object>> deleteAvatar(
            Authentication authentication
    ) {
        if (!isAuthenticated(authentication)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Map<String, Object> response =
                userMediaStorageService.deleteAvatar(
                        authentication.getName()
                );

        return ResponseEntity.ok(response);
    }

    /**
     * Remove the authenticated user's banner.
     */
    @DeleteMapping("/me/banner")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<Map<String, Object>> deleteBanner(
            Authentication authentication
    ) {
        if (!isAuthenticated(authentication)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Map<String, Object> response =
                userMediaStorageService.deleteBanner(
                        authentication.getName()
                );

        return ResponseEntity.ok(response);
    }

    /**
     * Check whether the current authentication is valid.
     */
    private boolean isAuthenticated(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && authentication.getName() != null
                && !authentication.getName().isBlank()
                && !"anonymousUser".equals(authentication.getPrincipal());
    }
}
