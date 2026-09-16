package com.alejandro.mtousers.service;

import com.alejandro.mtousers.dto.ProfileResponse;
import com.alejandro.mtousers.dto.ProfileSummaryResponse;

import java.util.List;

/** Perfiles (roles compuestos de realm) y su asignación a usuarios. */
public interface ProfileService {

    List<ProfileSummaryResponse> listProfiles();

    ProfileResponse getProfile(String profileName);

    List<ProfileSummaryResponse> getUserProfiles(String userId);

    List<ProfileSummaryResponse> assignProfile(String userId, String profileName);

    List<ProfileSummaryResponse> removeProfile(String userId, String profileName);
}
