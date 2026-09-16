package com.alejandro.mtousers.keycloak;

import com.alejandro.mtousers.dto.UserSearchCriteria;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.MappingsRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import java.util.List;
import java.util.Set;

/**
 * Única puerta a la Admin API de Keycloak. Los servicios hablan con esta interfaz y nunca con los
 * recursos del admin client ({@code RealmResource}, {@code UserResource}...): así la biblioteca
 * queda en un solo paquete y los servicios se prueban con un doble de esta interfaz.
 *
 * <p>Las representaciones de Keycloak sí cruzan por aquí, como valores opacos que los mappers
 * traducen a los DTOs de la API. Ninguna sale por la API.</p>
 *
 * <p>Toda llamada traduce los fallos de Keycloak a excepciones de negocio: 404 del usuario a
 * {@code UserNotFoundException}, 409 a {@code UserAlreadyExistsException}, 400 a
 * {@code KeycloakRequestException}, rechazo de la cuenta de servicio a
 * {@code KeycloakAccessException}, otros errores a {@code KeycloakUpstreamException} y la falta de
 * respuesta a {@code KeycloakUnavailableException}.</p>
 */
public interface KeycloakAdminGateway {

    List<UserRepresentation> searchUsers(UserSearchCriteria criteria);

    int countUsers(UserSearchCriteria criteria);

    UserRepresentation findUser(String userId);

    /** @return el id que Keycloak asignó al usuario nuevo */
    String createUser(UserRepresentation user);

    void updateUser(String userId, UserRepresentation user);

    void deleteUser(String userId);

    void resetPassword(String userId, String password, boolean temporary);

    void executeActionsEmail(String userId, List<String> actions, Integer lifespanSeconds, String clientId, String redirectUri);

    List<ClientRepresentation> listClients();

    /** @param clientId el nombre del cliente ({@code mto-stock-api}), no su UUID */
    ClientRepresentation findClient(String clientId);

    List<RoleRepresentation> listClientRoles(String clientUuid);

    MappingsRepresentation getUserRoleMappings(String userId);

    void addClientRoles(String userId, String clientUuid, List<RoleRepresentation> roles);

    void removeClientRoles(String userId, String clientUuid, List<RoleRepresentation> roles);

    List<RoleRepresentation> listRealmRoles();

    RoleRepresentation findRealmRole(String roleName);

    /** Los roles que un rol compuesto de realm contiene, de realm y de cliente ({@code clientRole=true}, {@code containerId} = UUID del cliente). */
    Set<RoleRepresentation> getRealmRoleComposites(String roleName);

    List<RoleRepresentation> getUserRealmRoles(String userId);

    void addRealmRoles(String userId, List<RoleRepresentation> roles);

    void removeRealmRoles(String userId, List<RoleRepresentation> roles);
}
