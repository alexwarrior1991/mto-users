package com.alejandro.mtousers.mapper;

import com.alejandro.mtousers.dto.ClientResponse;
import com.alejandro.mtousers.dto.ClientRoleAssignment;
import com.alejandro.mtousers.dto.ClientRoleResponse;
import com.alejandro.mtousers.dto.UserRolesResponse;
import org.keycloak.representations.idm.ClientMappingsRepresentation;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.mapstruct.Mapper;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Clientes, roles de cliente y asignaciones de un usuario.
 */
@Mapper(config = MapStructCentralConfig.class)
public interface RoleMapper {

    ClientResponse toClientResponse(ClientRepresentation client);

    List<ClientResponse> toClientResponses(List<ClientRepresentation> clients);

    ClientRoleResponse toRoleResponse(RoleRepresentation role);

    List<ClientRoleResponse> toRoleResponses(List<RoleRepresentation> roles);

    /**
     * Las asignaciones de Keycloak vienen como mapa {@code clientId → {id, client, mappings}}; la API
     * las devuelve como lista ordenada por cliente, con los nombres de rol ordenados.
     */
    default UserRolesResponse toUserRolesResponse(List<RoleRepresentation> realmMappings,
                                                  Map<String, ClientMappingsRepresentation> clientMappings) {
        List<String> realmRoles = realmMappings == null ? List.of() : realmMappings.stream()
                .map(RoleRepresentation::getName)
                .sorted()
                .toList();
        List<ClientRoleAssignment> clientRoles = clientMappings == null ? List.of() : clientMappings.entrySet().stream()
                .map(entry -> new ClientRoleAssignment(entry.getKey(), roleNames(entry.getValue())))
                .sorted(Comparator.comparing(ClientRoleAssignment::clientId))
                .toList();
        return new UserRolesResponse(realmRoles, clientRoles);
    }

    default List<String> roleNames(ClientMappingsRepresentation mappings) {
        if (mappings == null || mappings.getMappings() == null) {
            return List.of();
        }
        return mappings.getMappings().stream().map(RoleRepresentation::getName).sorted().toList();
    }
}
