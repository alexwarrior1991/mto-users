package com.alejandro.mtousers.dto;

import java.util.List;

/**
 * Roles asignados <em>directamente</em> a un usuario: los de realm (entre ellos sus perfiles) y los
 * de cliente. Los roles que le llegan por un perfil no aparecen aquí como roles de cliente —los
 * expande Keycloak al emitir el token—; para verlos, consúltese el perfil.
 */
public record UserRolesResponse(List<String> realmRoles, List<ClientRoleAssignment> clientRoles) {

    public UserRolesResponse {
        realmRoles = realmRoles == null ? List.of() : List.copyOf(realmRoles);
        clientRoles = clientRoles == null ? List.of() : List.copyOf(clientRoles);
    }
}
