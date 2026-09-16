package com.alejandro.mtousers.service.impl;

import com.alejandro.mtousers.configuration.keycloak.KeycloakAdminProperties;
import com.alejandro.mtousers.dto.ClientResponse;
import com.alejandro.mtousers.dto.ClientRoleResponse;
import com.alejandro.mtousers.dto.RoleNamesRequest;
import com.alejandro.mtousers.dto.UserResponse;
import com.alejandro.mtousers.dto.UserRolesResponse;
import com.alejandro.mtousers.exception.ProtectedClientException;
import com.alejandro.mtousers.exception.RoleNotFoundException;
import com.alejandro.mtousers.keycloak.KeycloakAdminGateway;
import com.alejandro.mtousers.mapper.RoleMapper;
import com.alejandro.mtousers.mapper.UserMapper;
import com.alejandro.mtousers.service.AdminAuditLog;
import com.alejandro.mtousers.service.AdminAuditLog.AdminAction;
import com.alejandro.mtousers.service.RoleService;
import org.keycloak.representations.idm.ClientMappingsRepresentation;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.MappingsRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
class RoleServiceImpl implements RoleService {

    private final KeycloakAdminGateway keycloak;
    private final KeycloakAdminProperties properties;
    private final RoleMapper roleMapper;
    private final UserMapper userMapper;
    private final AdminAuditLog audit;

    RoleServiceImpl(KeycloakAdminGateway keycloak, KeycloakAdminProperties properties, RoleMapper roleMapper,
                    UserMapper userMapper, AdminAuditLog audit) {
        this.keycloak = keycloak;
        this.properties = properties;
        this.roleMapper = roleMapper;
        this.userMapper = userMapper;
        this.audit = audit;
    }

    @Override
    public List<ClientResponse> listClients() {
        List<ClientRepresentation> clients = keycloak.listClients().stream()
                .filter(client -> !properties.isProtectedClient(client.getClientId()))
                .sorted(Comparator.comparing(ClientRepresentation::getClientId))
                .toList();
        return roleMapper.toClientResponses(clients);
    }

    @Override
    public List<ClientRoleResponse> listClientRoles(String clientId) {
        ClientRepresentation client = resolveClient(clientId);
        List<RoleRepresentation> roles = keycloak.listClientRoles(client.getId()).stream()
                .sorted(Comparator.comparing(RoleRepresentation::getName))
                .toList();
        return roleMapper.toRoleResponses(roles);
    }

    @Override
    public UserRolesResponse getUserRoles(String userId) {
        MappingsRepresentation mappings = keycloak.getUserRoleMappings(userId);
        return toResponse(mappings);
    }

    @Override
    public UserRolesResponse addClientRoles(String userId, String clientId, RoleNamesRequest request) {
        ClientRepresentation client = resolveClient(clientId);
        List<RoleRepresentation> roles = resolveRoles(client, request.roles());
        keycloak.addClientRoles(userId, client.getId(), roles);
        audit.record(AdminAction.CLIENT_ROLES_ADDED, userId, "client=" + clientId + " roles=" + names(roles));
        return getUserRoles(userId);
    }

    @Override
    public UserRolesResponse removeClientRoles(String userId, String clientId, RoleNamesRequest request) {
        ClientRepresentation client = resolveClient(clientId);
        List<RoleRepresentation> roles = resolveRoles(client, request.roles());
        keycloak.removeClientRoles(userId, client.getId(), roles);
        audit.record(AdminAction.CLIENT_ROLES_REMOVED, userId, "client=" + clientId + " roles=" + names(roles));
        return getUserRoles(userId);
    }

    /**
     * Quien tiene el rol <b>asignado directamente</b>. Keycloak no expande los compuestos en este
     * endpoint: quien tenga el rol porque se lo da un perfil no sale aqui. Sin recuento, porque
     * Keycloak no ofrece ninguno para los miembros de un rol.
     */
    @Override
    public List<UserResponse> listClientRoleMembers(String clientId, String roleName, int first, int max) {
        ClientRepresentation client = resolveClient(clientId);
        return userMapper.toResponses(keycloak.listClientRoleMembers(client.getId(), roleName, first, max));
    }

    /** Un cliente protegido no existe para esta API, ni para leer ni para escribir. */
    private ClientRepresentation resolveClient(String clientId) {
        if (properties.isProtectedClient(clientId)) {
            throw new ProtectedClientException(clientId);
        }
        return keycloak.findClient(clientId);
    }

    /**
     * Keycloak necesita el id de cada rol para mapearlo, así que se resuelven contra el catálogo del
     * cliente; un nombre que no exista es un 404 con la lista completa de los que faltan.
     */
    private List<RoleRepresentation> resolveRoles(ClientRepresentation client, List<String> requestedNames) {
        Map<String, RoleRepresentation> available = keycloak.listClientRoles(client.getId()).stream()
                .collect(Collectors.toMap(RoleRepresentation::getName, Function.identity(), (first, second) -> first));
        Set<String> names = new LinkedHashSet<>(requestedNames);
        List<String> missing = names.stream().filter(name -> !available.containsKey(name)).toList();
        if (!missing.isEmpty()) {
            throw new RoleNotFoundException(client.getClientId(), missing);
        }
        return names.stream().map(available::get).toList();
    }

    private UserRolesResponse toResponse(MappingsRepresentation mappings) {
        Map<String, ClientMappingsRepresentation> visibleClients = new LinkedHashMap<>();
        if (mappings.getClientMappings() != null) {
            mappings.getClientMappings().forEach((clientId, clientMappings) -> {
                if (!properties.isProtectedClient(clientId)) {
                    visibleClients.put(clientId, clientMappings);
                }
            });
        }
        return roleMapper.toUserRolesResponse(mappings.getRealmMappings(), visibleClients);
    }

    private static List<String> names(List<RoleRepresentation> roles) {
        return roles.stream().map(RoleRepresentation::getName).toList();
    }
}
