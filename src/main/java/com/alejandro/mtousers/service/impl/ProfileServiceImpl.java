package com.alejandro.mtousers.service.impl;

import com.alejandro.mtousers.configuration.profiles.ProfileProperties;
import com.alejandro.mtousers.dto.ClientRoleAssignment;
import com.alejandro.mtousers.dto.ProfileResponse;
import com.alejandro.mtousers.dto.ProfileSummaryResponse;
import com.alejandro.mtousers.dto.UserResponse;
import com.alejandro.mtousers.exception.ProfileNotFoundException;
import com.alejandro.mtousers.keycloak.KeycloakAdminGateway;
import com.alejandro.mtousers.mapper.ProfileMapper;
import com.alejandro.mtousers.mapper.UserMapper;
import com.alejandro.mtousers.service.AdminAuditLog;
import com.alejandro.mtousers.service.AdminAuditLog.AdminAction;
import com.alejandro.mtousers.service.ProfileService;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Un perfil es un rol compuesto de realm cuyo nombre empieza por el prefijo configurado
 * ({@code mto-} por defecto). Asignarlo es asignar ese único rol de realm: Keycloak lo expande en
 * los roles de cliente al emitir el token, y quitarlo quita exactamente lo que dio, sin tocar los
 * roles de cliente que el usuario tenga asignados directamente ni los que le den otros perfiles.
 * Nada se persiste aquí: la lista de perfiles y quién tiene cuál viven en Keycloak.
 */
@Service
class ProfileServiceImpl implements ProfileService {

    private final KeycloakAdminGateway keycloak;
    private final ProfileProperties properties;
    private final ProfileMapper profileMapper;
    private final UserMapper userMapper;
    private final AdminAuditLog audit;

    ProfileServiceImpl(KeycloakAdminGateway keycloak, ProfileProperties properties, ProfileMapper profileMapper,
                       UserMapper userMapper, AdminAuditLog audit) {
        this.keycloak = keycloak;
        this.properties = properties;
        this.profileMapper = profileMapper;
        this.userMapper = userMapper;
        this.audit = audit;
    }

    @Override
    public List<ProfileSummaryResponse> listProfiles() {
        return profileMapper.toSummaries(onlyProfiles(keycloak.listRealmRoles()));
    }

    @Override
    public ProfileResponse getProfile(String profileName) {
        RoleRepresentation role = resolveProfile(profileName);
        Set<RoleRepresentation> composites = keycloak.getRealmRoleComposites(profileName);

        List<String> realmRoles = composites.stream()
                .filter(composite -> !Boolean.TRUE.equals(composite.getClientRole()))
                .map(RoleRepresentation::getName)
                .sorted()
                .toList();

        // Los compuestos de cliente traen el UUID del cliente en containerId; la API habla de clientId.
        Map<String, List<String>> rolesByClientUuid = composites.stream()
                .filter(composite -> Boolean.TRUE.equals(composite.getClientRole()))
                .collect(Collectors.groupingBy(RoleRepresentation::getContainerId,
                        Collectors.mapping(RoleRepresentation::getName, Collectors.toList())));
        Map<String, String> clientIdsByUuid = rolesByClientUuid.isEmpty() ? Map.of() : keycloak.listClients().stream()
                .collect(Collectors.toMap(ClientRepresentation::getId, ClientRepresentation::getClientId, (first, second) -> first));

        Map<String, List<String>> rolesByClientId = new TreeMap<>();
        rolesByClientUuid.forEach((uuid, roles) ->
                rolesByClientId.put(clientIdsByUuid.getOrDefault(uuid, uuid), roles.stream().sorted().toList()));
        List<ClientRoleAssignment> clientRoles = rolesByClientId.entrySet().stream()
                .map(entry -> new ClientRoleAssignment(entry.getKey(), entry.getValue()))
                .toList();

        return profileMapper.toProfile(role, clientRoles, realmRoles);
    }

    @Override
    public List<ProfileSummaryResponse> getUserProfiles(String userId) {
        return profileMapper.toSummaries(onlyProfiles(keycloak.getUserRealmRoles(userId)));
    }

    @Override
    public List<ProfileSummaryResponse> assignProfile(String userId, String profileName) {
        RoleRepresentation role = resolveProfile(profileName);
        keycloak.addRealmRoles(userId, List.of(role));
        audit.record(AdminAction.PROFILE_ASSIGNED, userId, "profile=" + profileName);
        return getUserProfiles(userId);
    }

    @Override
    public List<ProfileSummaryResponse> removeProfile(String userId, String profileName) {
        RoleRepresentation role = resolveProfile(profileName);
        keycloak.removeRealmRoles(userId, List.of(role));
        audit.record(AdminAction.PROFILE_REMOVED, userId, "profile=" + profileName);
        return getUserProfiles(userId);
    }

    /**
     * Quien tiene el perfil asignado. Es el rol de realm, asi que aqui si salen todos los que lo
     * llevan; lo que no sale es quien tenga sus permisos por otra via. Sin recuento: Keycloak no
     * ofrece ninguno para los miembros de un rol.
     */
    @Override
    public List<UserResponse> listProfileMembers(String profileName, int first, int max) {
        resolveProfile(profileName);
        return userMapper.toResponses(keycloak.listRealmRoleMembers(profileName, first, max));
    }

    /** Un rol de realm que no es perfil no existe para esta API, aunque exista en Keycloak. */
    private RoleRepresentation resolveProfile(String profileName) {
        if (!properties.isProfile(profileName)) {
            throw new ProfileNotFoundException(profileName);
        }
        return keycloak.findRealmRole(profileName);
    }

    private List<RoleRepresentation> onlyProfiles(List<RoleRepresentation> realmRoles) {
        return realmRoles.stream()
                .filter(role -> properties.isProfile(role.getName()))
                .sorted(Comparator.comparing(RoleRepresentation::getName))
                .collect(Collectors.collectingAndThen(Collectors.toList(), Function.identity()));
    }
}
