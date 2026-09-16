package com.alejandro.mtousers.keycloak;

import com.alejandro.mtousers.dto.UserSearchCriteria;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.MappingsRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.representations.idm.UserSessionRepresentation;

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

    /**
     * Quién tiene ese rol de cliente <b>asignado directamente</b>. Keycloak no expande los
     * compuestos aquí: un usuario que tenga el rol porque se lo da un perfil no aparece.
     */
    List<UserRepresentation> listClientRoleMembers(String clientUuid, String roleName, int first, int max);

    MappingsRepresentation getUserRoleMappings(String userId);

    void addClientRoles(String userId, String clientUuid, List<RoleRepresentation> roles);

    void removeClientRoles(String userId, String clientUuid, List<RoleRepresentation> roles);

    List<RoleRepresentation> listRealmRoles();

    RoleRepresentation findRealmRole(String roleName);

    /** Quién tiene ese rol de realm asignado directamente. */
    List<UserRepresentation> listRealmRoleMembers(String roleName, int first, int max);

    /** Los roles que un rol compuesto de realm contiene, de realm y de cliente ({@code clientRole=true}, {@code containerId} = UUID del cliente). */
    Set<RoleRepresentation> getRealmRoleComposites(String roleName);

    List<RoleRepresentation> getUserRealmRoles(String userId);

    void addRealmRoles(String userId, List<RoleRepresentation> roles);

    void removeRealmRoles(String userId, List<RoleRepresentation> roles);

    List<UserSessionRepresentation> listUserSessions(String userId);

    /**
     * Los clientes para los que el usuario tiene un <b>token offline</b> vivo, por su UUID.
     *
     * <p>Keycloak no ofrece «las sesiones offline de este usuario»: las sesiones offline se
     * consultan cliente a cliente. Esta lista es el índice para no preguntar por todos: sale de los
     * consentimientos del usuario, donde cada token offline deja una concesión {@code Offline Token}
     * con el cliente al que pertenece.</p>
     */
    List<String> findClientsWithOfflineTokens(String userId);

    /** Las sesiones offline del usuario en ese cliente. */
    List<UserSessionRepresentation> listOfflineSessions(String userId, String clientUuid);

    /**
     * Cierra todas las sesiones <b>normales</b> del usuario. Idempotente: sin sesiones abiertas no
     * es un error. No toca las offline: para esas, {@link #deleteSession(String, boolean)}.
     */
    void logoutUser(String userId);

    /**
     * Cierra una sola sesión. La sesión pertenece al realm, no al usuario: ver la implementación.
     * El {@code offline} tiene que coincidir con la clase de sesión: Keycloak responde 404 si se
     * pide borrar una offline como normal, o al revés.
     */
    void deleteSession(String sessionId, boolean offline);

    /** Las credenciales del usuario. Keycloak nunca devuelve el secreto, solo sus metadatos. */
    List<CredentialRepresentation> listCredentials(String userId);

    /** Quita una credencial del usuario. Un id que no sea suyo es un 404, lo comprueba Keycloak. */
    void deleteCredential(String userId, String credentialId);
}
