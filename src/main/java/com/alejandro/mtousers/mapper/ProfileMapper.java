package com.alejandro.mtousers.mapper;

import com.alejandro.mtousers.dto.ClientRoleAssignment;
import com.alejandro.mtousers.dto.ProfileResponse;
import com.alejandro.mtousers.dto.ProfileSummaryResponse;
import org.keycloak.representations.idm.RoleRepresentation;
import org.mapstruct.Mapper;

import java.util.List;

/**
 * Perfiles: roles compuestos de realm vistos como lo que conceden.
 */
@Mapper(config = MapStructCentralConfig.class)
public interface ProfileMapper {

    ProfileSummaryResponse toSummary(RoleRepresentation role);

    List<ProfileSummaryResponse> toSummaries(List<RoleRepresentation> roles);

    default ProfileResponse toProfile(RoleRepresentation role, List<ClientRoleAssignment> clientRoles, List<String> realmRoles) {
        return new ProfileResponse(role.getName(), role.getDescription(), clientRoles, realmRoles);
    }
}
