package com.alejandro.mtousers.controller;

import com.alejandro.mtousers.configuration.security.SecurityConfiguration;
import com.alejandro.mtousers.dto.ProfileResponse;
import com.alejandro.mtousers.dto.ProfileSummaryResponse;
import com.alejandro.mtousers.service.ProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Perfiles: el catálogo ({@code /profiles/...}) y los de cada usuario ({@code /{userId}/profiles/...}).
 */
@RestController
@RequestMapping(SecurityConfiguration.API)
@Tag(name = "Profiles")
public class ProfileController {

    static final String PROFILE_NAME_PATTERN = "[A-Za-z0-9._-]{1,255}";
    static final String PROFILE_NAME_MESSAGE = "must be a profile name";

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping("/profiles")
    @Operation(summary = "List the profiles", description = "The composite realm roles of the platform (prefix mto- by default).")
    public List<ProfileSummaryResponse> listProfiles() {
        return profileService.listProfiles();
    }

    @GetMapping("/profiles/{profileName}")
    @Operation(summary = "What a profile grants", description = "Client roles grouped by client, and nested realm roles if any.")
    public ProfileResponse getProfile(
            @PathVariable @Pattern(regexp = PROFILE_NAME_PATTERN, message = PROFILE_NAME_MESSAGE) String profileName) {
        return profileService.getProfile(profileName);
    }

    @GetMapping("/{userId}/profiles")
    @Operation(summary = "Profiles of a user")
    public List<ProfileSummaryResponse> getUserProfiles(
            @PathVariable @Pattern(regexp = UserController.USER_ID_PATTERN, message = UserController.USER_ID_MESSAGE) String userId) {
        return profileService.getUserProfiles(userId);
    }

    @PutMapping("/{userId}/profiles/{profileName}")
    @Operation(summary = "Assign a profile to a user", description = "Idempotent. Returns the profiles of the user.")
    public List<ProfileSummaryResponse> assignProfile(
            @PathVariable @Pattern(regexp = UserController.USER_ID_PATTERN, message = UserController.USER_ID_MESSAGE) String userId,
            @PathVariable @Pattern(regexp = PROFILE_NAME_PATTERN, message = PROFILE_NAME_MESSAGE) String profileName) {
        return profileService.assignProfile(userId, profileName);
    }

    @DeleteMapping("/{userId}/profiles/{profileName}")
    @Operation(summary = "Remove a profile from a user", description = "Removes exactly what the profile granted. Returns the profiles of the user.")
    public List<ProfileSummaryResponse> removeProfile(
            @PathVariable @Pattern(regexp = UserController.USER_ID_PATTERN, message = UserController.USER_ID_MESSAGE) String userId,
            @PathVariable @Pattern(regexp = PROFILE_NAME_PATTERN, message = PROFILE_NAME_MESSAGE) String profileName) {
        return profileService.removeProfile(userId, profileName);
    }
}
