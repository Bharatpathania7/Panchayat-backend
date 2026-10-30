
        package com.panchayat.backend.user.controller;

import com.panchayat.backend.user.dto.UserStatsResponse;
import com.panchayat.backend.user.dto.UserProfileResponse;
import com.panchayat.backend.user.dto.PublicUserProfileResponse;
import com.panchayat.backend.user.dto.UpdateProfileRequest;
import com.panchayat.backend.user.service.UserProfileService;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/users")
public class UserProfileController {

    private final UserProfileService userProfileService;

    public UserProfileController(
            UserProfileService userProfileService
    ) {
        this.userProfileService = userProfileService;
    }

    /**
     * Get the authenticated user's complete profile.
     */
    @GetMapping("/me")
    public ResponseEntity<UserProfileResponse> getMyProfile(
            Authentication authentication
    ) {
        if (authentication == null
                || !authentication.isAuthenticated()) {
            return ResponseEntity.status(401).build();
        }

        UserProfileResponse response =
                userProfileService.getMyProfile(
                        authentication.getName()
                );

        return ResponseEntity.ok(response);
    }

    /**
     * Get another user's public profile.
     * Private information such as email and date of birth
     * is excluded from the response.
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
    public ResponseEntity<UserProfileResponse> updateMyProfile(
            Authentication authentication,
            @Valid @RequestBody UpdateProfileRequest request
    ) {
        if (authentication == null
                || !authentication.isAuthenticated()) {
            return ResponseEntity.status(401).build();
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
    public ResponseEntity<UserStatsResponse> getMyStats(
            Authentication authentication
    ) {
        if (authentication == null
                || !authentication.isAuthenticated()) {
            return ResponseEntity.status(401).build();
        }

        UserStatsResponse response =
                userProfileService.getMyStats(
                        authentication.getName()
                );

        return ResponseEntity.ok(response);
    }
}
