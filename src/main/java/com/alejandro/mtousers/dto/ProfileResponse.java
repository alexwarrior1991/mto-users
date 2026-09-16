package com.alejandro.mtousers.dto;

import java.util.List;

/**
 * Un perfil con lo que concede: los roles de cliente, agrupados por cliente, y los roles de realm
 * que anida, si los hay.
 */
public record ProfileResponse(
        String name,
        String description,
        List<ClientRoleAssignment> clientRoles,
        List<String> realmRoles
) {

    public ProfileResponse {
        clientRoles = clientRoles == null ? List.of() : List.copyOf(clientRoles);
        realmRoles = realmRoles == null ? List.of() : List.copyOf(realmRoles);
    }
}
