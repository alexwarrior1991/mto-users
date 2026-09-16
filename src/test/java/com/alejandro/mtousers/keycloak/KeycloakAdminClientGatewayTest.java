package com.alejandro.mtousers.keycloak;

import com.alejandro.mtousers.configuration.keycloak.KeycloakAdminProperties;
import com.alejandro.mtousers.dto.UserSearchCriteria;
import com.alejandro.mtousers.exception.ClientNotFoundException;
import com.alejandro.mtousers.exception.KeycloakAccessException;
import com.alejandro.mtousers.exception.KeycloakRequestException;
import com.alejandro.mtousers.exception.KeycloakUnavailableException;
import com.alejandro.mtousers.exception.KeycloakUpstreamException;
import com.alejandro.mtousers.exception.ProfileNotFoundException;
import com.alejandro.mtousers.exception.UserAlreadyExistsException;
import com.alejandro.mtousers.exception.UserNotFoundException;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.ClientResource;
import org.keycloak.admin.client.resource.ClientsResource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.RoleMappingResource;
import org.keycloak.admin.client.resource.RoleResource;
import org.keycloak.admin.client.resource.RoleScopeResource;
import org.keycloak.admin.client.resource.RolesResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.mockito.ArgumentCaptor;

import java.net.ConnectException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * La traducción de lo que el admin client devuelve o lanza a las excepciones de negocio, con la
 * cadena de recursos sustituida por dobles. Lo que se ve contra un Keycloak de verdad está en
 * {@code KeycloakUsersIT}.
 */
class KeycloakAdminClientGatewayTest {

    private static final String USER_ID = "2f1c9d1e-0000-4000-8000-000000000001";

    private final Keycloak keycloak = mock(Keycloak.class);
    private final RealmResource realm = mock(RealmResource.class);
    private final UsersQueryResource usersQuery = mock(UsersQueryResource.class);
    private final UsersResource users = mock(UsersResource.class);
    private final UserResource user = mock(UserResource.class);
    private final ClientsResource clients = mock(ClientsResource.class);
    private final RolesResource realmRoles = mock(RolesResource.class);

    private KeycloakAdminClientGateway gateway;

    @BeforeEach
    void setUp() {
        when(keycloak.realm("mto")).thenReturn(realm);
        when(keycloak.proxy(eq(UsersQueryResource.class), any(URI.class))).thenReturn(usersQuery);
        when(realm.users()).thenReturn(users);
        when(realm.clients()).thenReturn(clients);
        when(realm.roles()).thenReturn(realmRoles);
        when(users.get(USER_ID)).thenReturn(user);
        gateway = new KeycloakAdminClientGateway(keycloak, properties());
    }

    @Test
    void theUsersQueryProxyPointsAtTheUsersEndpointOfTheRealm() {
        verify(keycloak).proxy(UsersQueryResource.class, URI.create("http://kc:8080/admin/realms/mto/users"));
    }

    @Test
    void searchPassesEveryFilterAndTurnsBlanksIntoNulls() {
        when(usersQuery.search("garcia", null, null, false, null, 20, 10, false)).thenReturn(List.of(new UserRepresentation()));
        when(usersQuery.count("garcia", null, null, false, null)).thenReturn(null);

        UserSearchCriteria criteria = new UserSearchCriteria("garcia", " ", "", false, null, 20, 10);

        assertEquals(1, gateway.searchUsers(criteria).size());
        assertEquals(0, gateway.countUsers(criteria), "Un count sin cuerpo cuenta como cero, no como error");
    }

    @Test
    void aMissingUserIs404WhereverItIsLookedFor() {
        when(user.toRepresentation()).thenThrow(new NotFoundException());
        doThrow(new NotFoundException()).when(user).remove();
        doThrow(new NotFoundException()).when(user).update(any());
        doThrow(new NotFoundException()).when(user).resetPassword(any());

        assertThrows(UserNotFoundException.class, () -> gateway.findUser(USER_ID));
        assertThrows(UserNotFoundException.class, () -> gateway.deleteUser(USER_ID));
        assertThrows(UserNotFoundException.class, () -> gateway.updateUser(USER_ID, new UserRepresentation()));
        assertThrows(UserNotFoundException.class, () -> gateway.resetPassword(USER_ID, "Secreta.123", true));
    }

    @Test
    void creationReturnsTheIdFromTheLocationHeaderAndClosesTheResponse() {
        Response created = spy(Response.created(URI.create("http://kc:8080/admin/realms/mto/users/" + USER_ID)).build());
        when(users.create(any())).thenReturn(created);

        assertEquals(USER_ID, gateway.createUser(new UserRepresentation()));
        verify(created).close();
    }

    @Test
    void aDuplicateUserIs409WithKeycloaksOwnMessage() {
        when(users.create(any())).thenReturn(json(409, Map.of("errorMessage", "User exists with same username")));

        UserAlreadyExistsException conflict = assertThrows(UserAlreadyExistsException.class, () -> gateway.createUser(new UserRepresentation()));
        assertEquals("User exists with same username", conflict.getMessage());
    }

    @Test
    void anInvalidRepresentationIs400WithKeycloaksOwnMessage() {
        when(users.create(any())).thenReturn(json(400, Map.of("errorMessage", "error-user-attribute-required")));

        assertEquals("error-user-attribute-required",
                assertThrows(KeycloakRequestException.class, () -> gateway.createUser(new UserRepresentation())).getMessage());
    }

    /** La política de contraseñas responde con el formato OAuth ({@code error_description}) y sigue siendo un 400 del cliente. */
    @Test
    void aPasswordPolicyViolationIs400WithTheDescription() {
        doThrow(new BadRequestException(json(400, Map.of("error", "invalidPasswordMinLengthMessage",
                "error_description", "Invalid password: minimum length 8.")))).when(user).resetPassword(any());

        KeycloakRequestException rejected = assertThrows(KeycloakRequestException.class,
                () -> gateway.resetPassword(USER_ID, "corta", true));
        assertEquals("Invalid password: minimum length 8.", rejected.getMessage());
    }

    @Test
    void aRejectedServiceAccountIs502AndNamesTheClientToFix() {
        when(user.toRepresentation()).thenThrow(new NotAuthorizedException(json(401, Map.of(
                "error", "unauthorized_client", "error_description", "Invalid client or Invalid client credentials"))));

        KeycloakAccessException rejected = assertThrows(KeycloakAccessException.class, () -> gateway.findUser(USER_ID));
        assertTrue(rejected.getMessage().contains("mto-users-svc"));
        assertTrue(rejected.getMessage().contains("Invalid client or Invalid client credentials"));
    }

    @Test
    void aTokenEndpointErrorReportedAs400IsStillTheServiceAccount() {
        when(user.toRepresentation()).thenThrow(new BadRequestException(json(400, Map.of("error", "invalid_client"))));

        assertThrows(KeycloakAccessException.class, () -> gateway.findUser(USER_ID));
    }

    @Test
    void aServerErrorIs502WithTheDetail() {
        doThrow(new InternalServerErrorException(json(500, Map.of("errorMessage", "Failed to send execute actions email"))))
                .when(user).executeActionsEmail(any(), any(), any(), anyList());

        KeycloakUpstreamException failure = assertThrows(KeycloakUpstreamException.class,
                () -> gateway.executeActionsEmail(USER_ID, List.of("UPDATE_PASSWORD"), null, null, null));
        assertTrue(failure.getMessage().contains("HTTP 500"));
        assertTrue(failure.getMessage().contains("Failed to send execute actions email"));
    }

    @Test
    void noAnswerIs503() {
        when(user.toRepresentation()).thenThrow(new ProcessingException(new ConnectException("Connection refused")));

        KeycloakUnavailableException unavailable = assertThrows(KeycloakUnavailableException.class, () -> gateway.findUser(USER_ID));
        assertTrue(unavailable.getMessage().contains("Connection refused"));
    }

    @Test
    void resetPasswordBuildsAPasswordCredential() {
        gateway.resetPassword(USER_ID, "Secreta.123", false);

        ArgumentCaptor<CredentialRepresentation> credential = ArgumentCaptor.forClass(CredentialRepresentation.class);
        verify(user).resetPassword(credential.capture());
        assertEquals(CredentialRepresentation.PASSWORD, credential.getValue().getType());
        assertEquals("Secreta.123", credential.getValue().getValue());
        assertEquals(false, credential.getValue().isTemporary());
    }

    @Test
    void actionsEmailPassesClientRedirectAndLifespan() {
        gateway.executeActionsEmail(USER_ID, List.of("UPDATE_PASSWORD", "VERIFY_EMAIL"), 3600, "mto-frontend", "http://localhost:4200/");
        gateway.executeActionsEmail(USER_ID, List.of("VERIFY_EMAIL"), null, "", null);

        verify(user).executeActionsEmail("mto-frontend", "http://localhost:4200/", 3600, List.of("UPDATE_PASSWORD", "VERIFY_EMAIL"));
        verify(user).executeActionsEmail(null, null, null, List.of("VERIFY_EMAIL"));
    }

    @Test
    void clientsAreFoundByExactClientIdOr404() {
        ClientRepresentation stock = client("uuid-stock", "mto-stock-api");
        when(clients.findByClientId("mto-stock-api")).thenReturn(List.of(stock));
        when(clients.findByClientId("mto-nope")).thenReturn(List.of());

        assertSame(stock, gateway.findClient("mto-stock-api"));
        assertThrows(ClientNotFoundException.class, () -> gateway.findClient("mto-nope"));
    }

    @Test
    void clientRoleMappingsGoToTheClientLevelOfTheUser() {
        RoleMappingResource mappings = mock(RoleMappingResource.class);
        RoleScopeResource clientLevel = mock(RoleScopeResource.class);
        when(user.roles()).thenReturn(mappings);
        when(mappings.clientLevel("uuid-stock")).thenReturn(clientLevel);
        List<RoleRepresentation> roles = List.of(role("r1", "stock-read"));

        gateway.addClientRoles(USER_ID, "uuid-stock", roles);
        gateway.removeClientRoles(USER_ID, "uuid-stock", roles);

        verify(clientLevel).add(roles);
        verify(clientLevel).remove(roles);
    }

    @Test
    void realmRoleMappingsGoToTheRealmLevelOfTheUserAndAMissingUserIs404() {
        RoleMappingResource mappings = mock(RoleMappingResource.class);
        RoleScopeResource realmLevel = mock(RoleScopeResource.class);
        when(user.roles()).thenReturn(mappings);
        when(mappings.realmLevel()).thenReturn(realmLevel);
        List<RoleRepresentation> roles = List.of(role("p1", "mto-users-viewer"));
        doThrow(new NotFoundException()).when(realmLevel).remove(roles);

        gateway.addRealmRoles(USER_ID, roles);
        verify(realmLevel).add(roles);

        assertThrows(UserNotFoundException.class, () -> gateway.removeRealmRoles(USER_ID, roles));
    }

    @Test
    void aMissingRealmRoleIsAMissingProfile() {
        RoleResource roleResource = mock(RoleResource.class);
        when(realmRoles.get("mto-nope")).thenReturn(roleResource);
        when(roleResource.toRepresentation()).thenThrow(new NotFoundException());

        assertThrows(ProfileNotFoundException.class, () -> gateway.findRealmRole("mto-nope"));
    }

    @Test
    void clientRolesAreListedThroughTheClientResource() {
        ClientResource clientResource = mock(ClientResource.class);
        RolesResource clientRoles = mock(RolesResource.class);
        when(clients.get("uuid-stock")).thenReturn(clientResource);
        when(clientResource.roles()).thenReturn(clientRoles);
        when(clientRoles.list()).thenReturn(List.of(role("r1", "stock-read")));

        assertEquals("stock-read", gateway.listClientRoles("uuid-stock").getFirst().getName());
    }

    private static KeycloakAdminProperties properties() {
        return new KeycloakAdminProperties("http://kc:8080/", "mto", "mto-users-svc", "secret",
                Duration.ofSeconds(2), Duration.ofSeconds(10), 10, List.of("realm-management"));
    }

    private static Response json(int status, Map<String, String> body) {
        return Response.status(status).type(MediaType.APPLICATION_JSON_TYPE).entity(body).build();
    }

    private static ClientRepresentation client(String uuid, String clientId) {
        ClientRepresentation client = new ClientRepresentation();
        client.setId(uuid);
        client.setClientId(clientId);
        return client;
    }

    private static RoleRepresentation role(String id, String name) {
        RoleRepresentation role = new RoleRepresentation();
        role.setId(id);
        role.setName(name);
        return role;
    }
}
