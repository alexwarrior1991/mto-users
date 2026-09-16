package com.alejandro.mtousers.service.impl;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.alejandro.mtousers.configuration.keycloak.KeycloakAdminProperties;
import com.alejandro.mtousers.configuration.profiles.ProfileProperties;
import com.alejandro.mtousers.configuration.security.CurrentUserService;
import com.alejandro.mtousers.dto.ClientRoleAssignment;
import com.alejandro.mtousers.dto.CreateUserRequest;
import com.alejandro.mtousers.dto.ExecuteActionsEmailRequest;
import com.alejandro.mtousers.dto.PageResponse;
import com.alejandro.mtousers.dto.ProfileResponse;
import com.alejandro.mtousers.dto.ProfileSummaryResponse;
import com.alejandro.mtousers.dto.RequiredAction;
import com.alejandro.mtousers.dto.ResetPasswordRequest;
import com.alejandro.mtousers.dto.RoleNamesRequest;
import com.alejandro.mtousers.dto.UpdateUserRequest;
import com.alejandro.mtousers.dto.UserCredentialResponse;
import com.alejandro.mtousers.dto.UserResponse;
import com.alejandro.mtousers.dto.UserRolesResponse;
import com.alejandro.mtousers.dto.UserSearchCriteria;
import com.alejandro.mtousers.dto.UserSessionResponse;
import com.alejandro.mtousers.exception.CredentialNotFoundException;
import com.alejandro.mtousers.exception.InvalidSearchException;
import com.alejandro.mtousers.exception.ProfileNotFoundException;
import com.alejandro.mtousers.exception.ProtectedClientException;
import com.alejandro.mtousers.exception.RoleNotFoundException;
import com.alejandro.mtousers.exception.SessionNotFoundException;
import com.alejandro.mtousers.keycloak.KeycloakAdminGateway;
import com.alejandro.mtousers.mapper.ProfileMapper;
import com.alejandro.mtousers.mapper.RoleMapper;
import com.alejandro.mtousers.mapper.UserMapper;
import com.alejandro.mtousers.service.AdminAuditLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.ClientMappingsRepresentation;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.MappingsRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.representations.idm.UserSessionRepresentation;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Los tres servicios con la puerta a Keycloak sustituida por un doble y los mappers reales. La
 * auditoría se comprueba leyendo lo que el logger de auditoría escribió.
 */
class BusinessLayerTest {

    private static final String USER_ID = "2f1c9d1e-0000-4000-8000-000000000001";

    private final KeycloakAdminGateway keycloak = mock(KeycloakAdminGateway.class);
    private final AdminAuditLog audit = new AdminAuditLog(new CurrentUserService());
    private final ListAppender<ILoggingEvent> auditLines = new ListAppender<>();

    private final UserServiceImpl userService = new UserServiceImpl(keycloak, Mappers.getMapper(UserMapper.class), audit);
    private final RoleServiceImpl roleService = new RoleServiceImpl(keycloak, keycloakProperties(),
            Mappers.getMapper(RoleMapper.class), Mappers.getMapper(UserMapper.class), audit);
    private final ProfileServiceImpl profileService = new ProfileServiceImpl(keycloak,
            new ProfileProperties("mto-", List.of("mto-internal")), Mappers.getMapper(ProfileMapper.class),
            Mappers.getMapper(UserMapper.class), audit);

    @BeforeEach
    void captureAuditLog() {
        auditLines.start();
        ((Logger) LoggerFactory.getLogger("com.alejandro.mtousers.audit")).addAppender(auditLines);
    }

    @AfterEach
    void releaseAuditLog() {
        ((Logger) LoggerFactory.getLogger("com.alejandro.mtousers.audit")).detachAppender(auditLines);
    }

    // --- Usuarios ---------------------------------------------------------------------------------

    @Test
    void searchReturnsThePageWithTheTotalFromCount() {
        UserSearchCriteria criteria = new UserSearchCriteria("ana", null, null, true, null, null, 0, 20);
        when(keycloak.searchUsers(criteria)).thenReturn(List.of(user("ana.uno"), user("ana.dos")));
        when(keycloak.countUsers(criteria)).thenReturn(42);

        PageResponse<UserResponse> page = userService.search(criteria);

        assertEquals(2, page.content().size());
        assertEquals("ana.uno", page.content().getFirst().username());
        assertEquals(42, page.total());
        assertEquals(20, page.max());
    }

    /**
     * Keycloak descarta 'q' cuando llega con 'search', asi que la combinacion devolveria usuarios
     * que no tienen el atributo pedido. Se corta antes de preguntar.
     */
    @Test
    void searchingByTextAndByAttributeAtOnceIsRejectedInsteadOfAnsweringSomethingElse() {
        UserSearchCriteria both = new UserSearchCriteria("ana", null, null, null, null, List.of("departamento:ops"), 0, 20);

        InvalidSearchException rejected = assertThrows(InvalidSearchException.class, () -> userService.search(both));
        assertTrue(rejected.getMessage().contains("cannot be combined"));
        verify(keycloak, never()).searchUsers(any());

        // Un 'search' en blanco no cuenta como busqueda: el formulario que manda el parametro vacio
        // no puede quedarse sin el filtro por atributo.
        UserSearchCriteria blankSearch = new UserSearchCriteria("   ", null, null, null, null, List.of("departamento:ops"), 0, 20);
        assertFalse(blankSearch.hasSearch());
        when(keycloak.searchUsers(blankSearch)).thenReturn(List.of(user("ops.uno")));
        when(keycloak.countUsers(blankSearch)).thenReturn(1);
        assertEquals(1, userService.search(blankSearch).total());

        // Cada uno por su lado si vale, y el filtro por atributo tambien con username o enabled.
        UserSearchCriteria byAttribute = new UserSearchCriteria(null, null, null, true, null, List.of("departamento:ops"), 0, 20);
        when(keycloak.searchUsers(byAttribute)).thenReturn(List.of(user("ops.uno")));
        when(keycloak.countUsers(byAttribute)).thenReturn(1);
        assertEquals("ops.uno", userService.search(byAttribute).content().getFirst().username());
        assertEquals("departamento:ops", byAttribute.attributeQuery());

        // Y dos claves distintas si se combinan: Keycloak las une con Y.
        UserSearchCriteria twoKeys = new UserSearchCriteria(null, null, null, null, null,
                List.of("departamento:ops", "turno:noche"), 0, 20);
        assertEquals("departamento:ops turno:noche", twoKeys.attributeQuery());
        assertEquals(List.of(), twoKeys.repeatedAttributeKeys());
    }

    /**
     * Keycloak parsea {@code q} a un mapa, asi que una clave repetida pierde todos los pares menos
     * el ultimo. Igual que con {@code search}, se rechaza en vez de contestar otra cosa.
     */
    @Test
    void repeatingAnAttributeKeyIsRejectedBecauseKeycloakWouldKeepOnlyTheLastOne() {
        UserSearchCriteria repeated = new UserSearchCriteria(null, null, null, null, null,
                List.of("departamento:taller", "turno:noche", "departamento:obra"), 0, 20);
        assertEquals(List.of("departamento"), repeated.repeatedAttributeKeys());

        InvalidSearchException rejected = assertThrows(InvalidSearchException.class, () -> userService.search(repeated));
        assertTrue(rejected.getMessage().contains("departamento"), rejected.getMessage());
        assertTrue(rejected.getMessage().contains("cannot repeat a key"));
        verify(keycloak, never()).searchUsers(any());
        verify(keycloak, never()).countUsers(any());

        // Sin ':' el termino entero es la clave. El controlador no deja pasar uno asi, pero el
        // servicio no depende de esa validacion para no repetir filtros.
        assertEquals(List.of("solo-clave"), new UserSearchCriteria(null, null, null, null, null,
                List.of("solo-clave", "solo-clave"), 0, 20).repeatedAttributeKeys());
    }

    @Test
    void sessionsAreListedMappedAndClosed() {
        UserSessionRepresentation session = session("session-1", "10.0.0.9", Map.of("uuid-b", "mto-frontend", "uuid-a", "mto-users-api"));
        when(keycloak.listUserSessions(USER_ID)).thenReturn(List.of(session));

        var sessions = userService.listSessions(USER_ID);

        assertEquals(1, sessions.size());
        assertEquals("session-1", sessions.getFirst().id());
        assertEquals("10.0.0.9", sessions.getFirst().ipAddress());
        assertEquals(Instant.ofEpochMilli(1_700_000_000_000L), sessions.getFirst().startedAt());
        assertEquals(List.of("mto-frontend", "mto-users-api"), sessions.getFirst().clients(), "Por nombre de cliente y ordenados");

        userService.revokeSession(USER_ID, "session-1");
        verify(keycloak).deleteSession("session-1", false);
        assertTrue(onlyAuditLine().contains("action=SESSION_REVOKED"));
    }

    /**
     * El endpoint de Keycloak que cierra una sesion es del realm: con el id de la sesion de otra
     * persona cerraria la suya. Por eso se comprueba antes de quien es.
     */
    @Test
    void aSessionOfAnotherUserIsNotClosedThroughThisUser() {
        when(keycloak.listUserSessions(USER_ID)).thenReturn(List.of(session("session-1", "10.0.0.9", Map.of())));

        assertThrows(SessionNotFoundException.class, () -> userService.revokeSession(USER_ID, "session-de-otro"));
        verify(keycloak, never()).deleteSession(anyString(), org.mockito.ArgumentMatchers.anyBoolean());
        assertEquals(0, auditLines.list.size(), "Lo que no se hizo no se audita");
    }

    @Test
    void closingEverySessionIsIdempotentAndAudited() {
        userService.revokeAllSessions(USER_ID);

        verify(keycloak).logoutUser(USER_ID);
        assertTrue(onlyAuditLine().contains("action=ALL_SESSIONS_REVOKED"));
    }

    /**
     * Las sesiones offline no salen en /sessions ni las cierra el logout: hay que buscarlas cliente
     * a cliente, y los clientes salen de los consentimientos.
     */
    @Test
    void offlineSessionsAreGatheredClientByClientListedAndClosed() {
        when(keycloak.findClientsWithOfflineTokens(USER_ID)).thenReturn(List.of("uuid-frontend", "uuid-movil"));
        when(keycloak.listOfflineSessions(USER_ID, "uuid-frontend"))
                .thenReturn(List.of(session("offline-1", "10.0.0.9", Map.of("uuid-frontend", "mto-frontend"))));
        when(keycloak.listOfflineSessions(USER_ID, "uuid-movil"))
                .thenReturn(List.of(session("offline-2", "10.0.0.10", Map.of("uuid-movil", "mto-movil"))));

        var sessions = userService.listOfflineSessions(USER_ID);

        assertEquals(List.of("offline-1", "offline-2"), sessions.stream().map(UserSessionResponse::id).toList());
        assertEquals(List.of("mto-frontend"), sessions.getFirst().clients());
        verify(keycloak, never()).listUserSessions(USER_ID);

        userService.revokeOfflineSession(USER_ID, "offline-2");
        verify(keycloak).deleteSession("offline-2", true);
        assertTrue(onlyAuditLine().contains("action=OFFLINE_SESSION_REVOKED"));
    }

    @Test
    void anOfflineSessionOfAnotherUserIsNotClosedThroughThisUserEither() {
        when(keycloak.findClientsWithOfflineTokens(USER_ID)).thenReturn(List.of("uuid-frontend"));
        when(keycloak.listOfflineSessions(USER_ID, "uuid-frontend"))
                .thenReturn(List.of(session("offline-1", "10.0.0.9", Map.of())));

        assertThrows(SessionNotFoundException.class, () -> userService.revokeOfflineSession(USER_ID, "offline-de-otro"));
        verify(keycloak, never()).deleteSession(anyString(), org.mockito.ArgumentMatchers.anyBoolean());
        assertEquals(0, auditLines.list.size());
    }

    @Test
    void closingEveryOfflineSessionClosesThemOneByOneAndCountsThemInTheAuditLine() {
        when(keycloak.findClientsWithOfflineTokens(USER_ID)).thenReturn(List.of("uuid-frontend"));
        when(keycloak.listOfflineSessions(USER_ID, "uuid-frontend")).thenReturn(List.of(
                session("offline-1", "10.0.0.9", Map.of()), session("offline-2", "10.0.0.9", Map.of())));

        userService.revokeAllOfflineSessions(USER_ID);

        verify(keycloak).deleteSession("offline-1", true);
        verify(keycloak).deleteSession("offline-2", true);
        assertTrue(onlyAuditLine().contains("action=ALL_OFFLINE_SESSIONS_REVOKED"));
        assertTrue(onlyAuditLine().contains("sessions=2"));

        // Sin tokens offline no hay nada que cerrar y tampoco es un error.
        auditLines.list.clear();
        when(keycloak.findClientsWithOfflineTokens(USER_ID)).thenReturn(List.of());
        userService.revokeAllOfflineSessions(USER_ID);
        assertTrue(onlyAuditLine().contains("sessions=0"));
    }

    /**
     * Quitar una credencial se audita con su tipo, que es lo que distingue quitarle a alguien el
     * segundo factor de quitarle una contrasena vieja. Por eso se busca antes entre las suyas.
     */
    @Test
    void credentialsAreListedWithoutSecretsAndRemovedByIdWithTheirTypeAudited() {
        when(keycloak.listCredentials(USER_ID)).thenReturn(List.of(
                credential("cred-1", "password", null, 1_700_000_000_000L),
                credential("cred-2", "otp", "Movil de guardia", 1_700_000_060_000L)));

        var credentials = userService.listCredentials(USER_ID);

        assertEquals(List.of("password", "otp"), credentials.stream().map(UserCredentialResponse::type).toList());
        assertEquals("Movil de guardia", credentials.getLast().userLabel());
        assertEquals(Instant.ofEpochMilli(1_700_000_000_000L), credentials.getFirst().createdAt());

        userService.deleteCredential(USER_ID, "cred-2");

        verify(keycloak).deleteCredential(USER_ID, "cred-2");
        assertTrue(onlyAuditLine().contains("action=CREDENTIAL_DELETED"));
        assertTrue(onlyAuditLine().contains("type=otp"), onlyAuditLine());
    }

    @Test
    void aCredentialThatIsNotHisIsNotRemovedThroughHim() {
        when(keycloak.listCredentials(USER_ID)).thenReturn(List.of(credential("cred-1", "password", null, 1L)));

        assertThrows(CredentialNotFoundException.class, () -> userService.deleteCredential(USER_ID, "cred-de-otro"));
        verify(keycloak, never()).deleteCredential(anyString(), anyString());
        assertEquals(0, auditLines.list.size());
    }

    @Test
    void createDefaultsToEnabledCarriesTheTemporaryPasswordAsCredentialAndAuditsWithoutIt() {
        when(keycloak.createUser(any())).thenReturn(USER_ID);
        when(keycloak.findUser(USER_ID)).thenReturn(user("ana.nueva"));

        UserResponse created = userService.create(new CreateUserRequest("ana.nueva", "Ana", "Nueva", "ana@mto.local",
                null, null, Map.of("dept", List.of("ops")), List.of(RequiredAction.UPDATE_PASSWORD), "Secreta.123"));

        ArgumentCaptor<UserRepresentation> sent = ArgumentCaptor.forClass(UserRepresentation.class);
        verify(keycloak).createUser(sent.capture());
        assertEquals(Boolean.TRUE, sent.getValue().isEnabled(), "Keycloak crea deshabilitado por defecto; un alta se quiere activa");
        assertEquals("ana.nueva", sent.getValue().getUsername());
        assertEquals(List.of("UPDATE_PASSWORD"), sent.getValue().getRequiredActions());
        assertEquals("Secreta.123", sent.getValue().getCredentials().getFirst().getValue());
        assertEquals(Boolean.TRUE, sent.getValue().getCredentials().getFirst().isTemporary());
        assertEquals("ana.nueva", created.username());

        String line = onlyAuditLine();
        assertTrue(line.contains("action=USER_CREATED"));
        assertTrue(line.contains("targetUserId=" + USER_ID));
        assertTrue(line.contains("temporaryPassword=true"));
        assertFalse(line.contains("Secreta.123"), "La contraseña no puede aparecer en la auditoría");
    }

    @Test
    void createRespectsAnExplicitDisabledFlag() {
        when(keycloak.createUser(any())).thenReturn(USER_ID);
        when(keycloak.findUser(USER_ID)).thenReturn(user("ana.nueva"));

        userService.create(new CreateUserRequest("ana.nueva", null, null, null, null, false, null, null, null));

        ArgumentCaptor<UserRepresentation> sent = ArgumentCaptor.forClass(UserRepresentation.class);
        verify(keycloak).createUser(sent.capture());
        assertEquals(Boolean.FALSE, sent.getValue().isEnabled());
        assertEquals(null, sent.getValue().getCredentials());
    }

    @Test
    void updateReadsMergesAndWritesTheWholeRepresentation() {
        UserRepresentation existing = user("ana.uno");
        existing.setFirstName("Ana");
        existing.setLastName("Uno");
        existing.setEmail("ana@mto.local");
        existing.setAttributes(Map.of("dept", List.of("ops")));
        when(keycloak.findUser(USER_ID)).thenReturn(existing);

        userService.update(USER_ID, new UpdateUserRequest(null, "Dos", null, true, null));

        ArgumentCaptor<UserRepresentation> sent = ArgumentCaptor.forClass(UserRepresentation.class);
        verify(keycloak).updateUser(org.mockito.ArgumentMatchers.eq(USER_ID), sent.capture());
        assertEquals("Ana", sent.getValue().getFirstName(), "Lo que no viene se conserva");
        assertEquals("Dos", sent.getValue().getLastName());
        assertEquals("ana@mto.local", sent.getValue().getEmail());
        assertEquals(Boolean.TRUE, sent.getValue().isEmailVerified());
        assertEquals(List.of("ops"), sent.getValue().getAttributes().get("dept"));
        assertTrue(onlyAuditLine().contains("fields=lastName emailVerified"));
    }

    /** La linea de auditoria enumera lo que venia en la peticion, no lo que de verdad cambio. */
    @Test
    void theAuditLineOfAnUpdateNamesEveryFieldThatCame() {
        when(keycloak.findUser(USER_ID)).thenReturn(user("ana.uno"));

        userService.update(USER_ID, new UpdateUserRequest("Ana", "Uno", "ana@mto.local", true,
                Map.of("dept", List.of("ops"))));

        assertTrue(onlyAuditLine().contains("fields=firstName lastName email emailVerified attributes"), onlyAuditLine());
    }

    @Test
    void enablingAndDisablingOnlyTouchTheFlag() {
        UserRepresentation existing = user("ana.uno");
        existing.setEnabled(true);
        when(keycloak.findUser(USER_ID)).thenReturn(existing);

        userService.setEnabled(USER_ID, false);

        ArgumentCaptor<UserRepresentation> sent = ArgumentCaptor.forClass(UserRepresentation.class);
        verify(keycloak).updateUser(org.mockito.ArgumentMatchers.eq(USER_ID), sent.capture());
        assertEquals(Boolean.FALSE, sent.getValue().isEnabled());
        assertTrue(onlyAuditLine().contains("action=USER_DISABLED"));

        userService.setEnabled(USER_ID, true);
        verify(keycloak, org.mockito.Mockito.times(2)).updateUser(org.mockito.ArgumentMatchers.eq(USER_ID), sent.capture());
        assertEquals(Boolean.TRUE, sent.getValue().isEnabled());
        assertTrue(auditLines.list.getLast().getFormattedMessage().contains("action=USER_ENABLED"),
                "Cada sentido deja su propia accion");
    }

    @Test
    void resetPasswordIsTemporaryByDefaultAndNeverLogged() {
        userService.resetPassword(USER_ID, new ResetPasswordRequest("Secreta.123", null));

        verify(keycloak).resetPassword(USER_ID, "Secreta.123", true);
        String line = onlyAuditLine();
        assertTrue(line.contains("action=PASSWORD_RESET"));
        assertTrue(line.contains("temporary=true"));
        assertFalse(line.contains("Secreta.123"));
    }

    @Test
    void actionsEmailSendsTheActionNames() {
        userService.executeActionsEmail(USER_ID, new ExecuteActionsEmailRequest(
                List.of(RequiredAction.UPDATE_PASSWORD, RequiredAction.VERIFY_EMAIL), 600, "mto-frontend", "http://localhost:4200"));

        verify(keycloak).executeActionsEmail(USER_ID, List.of("UPDATE_PASSWORD", "VERIFY_EMAIL"), 600, "mto-frontend", "http://localhost:4200");
        assertTrue(onlyAuditLine().contains("actions=[UPDATE_PASSWORD, VERIFY_EMAIL]"));
    }

    @Test
    void deleteAudits() {
        userService.delete(USER_ID);

        verify(keycloak).deleteUser(USER_ID);
        assertTrue(onlyAuditLine().contains("action=USER_DELETED actor=unknown"));
    }

    // --- Roles ------------------------------------------------------------------------------------

    @Test
    void protectedClientsAreNeitherListedNorReadable() {
        when(keycloak.listClients()).thenReturn(List.of(client("u2", "mto-stock-api"), client("u1", "realm-management"), client("u3", "mto-configuration-api")));

        assertEquals(List.of("mto-configuration-api", "mto-stock-api"),
                roleService.listClients().stream().map(client -> client.clientId()).toList());
        assertThrows(ProtectedClientException.class, () -> roleService.listClientRoles("realm-management"));
        assertThrows(ProtectedClientException.class,
                () -> roleService.addClientRoles(USER_ID, "realm-management", new RoleNamesRequest(List.of("realm-admin"))));
        verify(keycloak, never()).addClientRoles(anyString(), anyString(), anyList());
    }

    @Test
    void addingRolesResolvesTheirIdsAndRejectsUnknownNamesAsAWhole() {
        when(keycloak.findClient("mto-stock-api")).thenReturn(client("uuid-stock", "mto-stock-api"));
        when(keycloak.listClientRoles("uuid-stock")).thenReturn(List.of(role("r1", "stock-read"), role("r2", "stock-write")));

        RoleNotFoundException missing = assertThrows(RoleNotFoundException.class,
                () -> roleService.addClientRoles(USER_ID, "mto-stock-api", new RoleNamesRequest(List.of("stock-read", "stock-fly", "stock-swim"))));
        assertTrue(missing.getMessage().contains("stock-fly, stock-swim"));
        verify(keycloak, never()).addClientRoles(anyString(), anyString(), anyList());

        MappingsRepresentation after = new MappingsRepresentation();
        after.setClientMappings(Map.of("mto-stock-api", clientMappings("mto-stock-api", "stock-write", "stock-read")));
        when(keycloak.getUserRoleMappings(USER_ID)).thenReturn(after);

        UserRolesResponse response = roleService.addClientRoles(USER_ID, "mto-stock-api", new RoleNamesRequest(List.of("stock-write", "stock-write")));

        ArgumentCaptor<List<RoleRepresentation>> sent = ArgumentCaptor.captor();
        verify(keycloak).addClientRoles(org.mockito.ArgumentMatchers.eq(USER_ID), org.mockito.ArgumentMatchers.eq("uuid-stock"), sent.capture());
        assertEquals(List.of("r2"), sent.getValue().stream().map(RoleRepresentation::getId).toList(), "Sin duplicados y con el id que Keycloak necesita");
        assertEquals(List.of(new ClientRoleAssignment("mto-stock-api", List.of("stock-read", "stock-write"))), response.clientRoles());
        assertTrue(onlyAuditLine().contains("action=CLIENT_ROLES_ADDED"));
        assertTrue(onlyAuditLine().contains("client=mto-stock-api roles=[stock-write]"));
    }

    @Test
    void removingRolesResolvesTheSameWayAndLeavesItsOwnAuditLine() {
        when(keycloak.findClient("mto-stock-api")).thenReturn(client("uuid-stock", "mto-stock-api"));
        when(keycloak.listClientRoles("uuid-stock")).thenReturn(List.of(role("r1", "stock-read"), role("r2", "stock-write")));

        // Quitar pasa por las mismas puertas que anadir: cliente protegido y nombre inexistente.
        assertThrows(ProtectedClientException.class,
                () -> roleService.removeClientRoles(USER_ID, "realm-management", new RoleNamesRequest(List.of("realm-admin"))));
        assertThrows(RoleNotFoundException.class,
                () -> roleService.removeClientRoles(USER_ID, "mto-stock-api", new RoleNamesRequest(List.of("stock-fly"))));
        verify(keycloak, never()).removeClientRoles(anyString(), anyString(), anyList());

        MappingsRepresentation after = new MappingsRepresentation();
        after.setClientMappings(Map.of("mto-stock-api", clientMappings("mto-stock-api", "stock-read")));
        when(keycloak.getUserRoleMappings(USER_ID)).thenReturn(after);

        UserRolesResponse response = roleService.removeClientRoles(USER_ID, "mto-stock-api", new RoleNamesRequest(List.of("stock-write")));

        ArgumentCaptor<List<RoleRepresentation>> sent = ArgumentCaptor.captor();
        verify(keycloak).removeClientRoles(org.mockito.ArgumentMatchers.eq(USER_ID), org.mockito.ArgumentMatchers.eq("uuid-stock"), sent.capture());
        assertEquals(List.of("r2"), sent.getValue().stream().map(RoleRepresentation::getId).toList());
        assertEquals(List.of(new ClientRoleAssignment("mto-stock-api", List.of("stock-read"))), response.clientRoles());
        assertTrue(onlyAuditLine().contains("action=CLIENT_ROLES_REMOVED"));
        assertTrue(onlyAuditLine().contains("client=mto-stock-api roles=[stock-write]"));
    }

    @Test
    void userRolesHideProtectedClientsAndKeepRealmRoles() {
        MappingsRepresentation mappings = new MappingsRepresentation();
        mappings.setRealmMappings(List.of(role("p1", "mto-users-viewer"), role("p0", "default-roles-mto")));
        mappings.setClientMappings(Map.of(
                "realm-management", clientMappings("realm-management", "view-users"),
                "mto-users-api", clientMappings("mto-users-api", "users-write", "users-read")));
        when(keycloak.getUserRoleMappings(USER_ID)).thenReturn(mappings);

        UserRolesResponse roles = roleService.getUserRoles(USER_ID);

        assertEquals(List.of("default-roles-mto", "mto-users-viewer"), roles.realmRoles());
        assertEquals(List.of(new ClientRoleAssignment("mto-users-api", List.of("users-read", "users-write"))), roles.clientRoles());
    }

    @Test
    void clientRoleMembersGoThroughTheClientAndRefuseProtectedClients() {
        when(keycloak.findClient("mto-stock-api")).thenReturn(client("uuid-stock", "mto-stock-api"));
        when(keycloak.listClientRoleMembers("uuid-stock", "stock-read", 0, 20)).thenReturn(List.of(user("almacen.lector")));

        assertEquals(List.of("almacen.lector"),
                roleService.listClientRoleMembers("mto-stock-api", "stock-read", 0, 20).stream().map(UserResponse::username).toList());
        assertThrows(ProtectedClientException.class, () -> roleService.listClientRoleMembers("realm-management", "realm-admin", 0, 20));
    }

    // --- Perfiles ---------------------------------------------------------------------------------

    @Test
    void profilesAreTheRealmRolesWithThePrefixMinusTheExcludedOnes() {
        when(keycloak.listRealmRoles()).thenReturn(List.of(role("a", "mto-warehouse-admin"), role("b", "default-roles-mto"),
                role("c", "mto-internal"), role("d", "mto-admin"), role("e", "offline_access")));

        assertEquals(List.of("mto-admin", "mto-warehouse-admin"),
                profileService.listProfiles().stream().map(ProfileSummaryResponse::name).toList());
    }

    @Test
    void aProfileShowsItsClientRolesGroupedByClientIdAndItsNestedRealmRoles() {
        RoleRepresentation profile = role("p", "mto-ops");
        profile.setDescription("Explotacion");
        when(keycloak.findRealmRole("mto-ops")).thenReturn(profile);
        when(keycloak.getRealmRoleComposites("mto-ops")).thenReturn(Set.of(
                clientRole("uuid-stock", "stock-read"), clientRole("uuid-stock", "ops-metrics"),
                clientRole("uuid-users", "ops-metrics"), role("n", "mto-viewer")));
        when(keycloak.listClients()).thenReturn(List.of(client("uuid-stock", "mto-stock-api"), client("uuid-users", "mto-users-api")));

        ProfileResponse response = profileService.getProfile("mto-ops");

        assertEquals("Explotacion", response.description());
        assertEquals(List.of(
                new ClientRoleAssignment("mto-stock-api", List.of("ops-metrics", "stock-read")),
                new ClientRoleAssignment("mto-users-api", List.of("ops-metrics"))), response.clientRoles());
        assertEquals(List.of("mto-viewer"), response.realmRoles());
    }

    @Test
    void aRealmRoleThatIsNotAProfileDoesNotExistForThisApi() {
        assertThrows(ProfileNotFoundException.class, () -> profileService.getProfile("default-roles-mto"));
        assertThrows(ProfileNotFoundException.class, () -> profileService.assignProfile(USER_ID, "offline_access"));
        assertThrows(ProfileNotFoundException.class, () -> profileService.assignProfile(USER_ID, "mto-internal"));
        verify(keycloak, never()).findRealmRole(anyString());
        verify(keycloak, never()).addRealmRoles(anyString(), anyList());
    }

    @Test
    void assigningAndRemovingAProfileMapsExactlyThatRealmRole() {
        RoleRepresentation profile = role("p1", "mto-users-viewer");
        when(keycloak.findRealmRole("mto-users-viewer")).thenReturn(profile);
        when(keycloak.getUserRealmRoles(USER_ID)).thenReturn(List.of(profile, role("x", "default-roles-mto")));

        List<ProfileSummaryResponse> afterAssign = profileService.assignProfile(USER_ID, "mto-users-viewer");
        List<ProfileSummaryResponse> afterRemove = profileService.removeProfile(USER_ID, "mto-users-viewer");

        verify(keycloak).addRealmRoles(USER_ID, List.of(profile));
        verify(keycloak).removeRealmRoles(USER_ID, List.of(profile));
        assertEquals(List.of("mto-users-viewer"), afterAssign.stream().map(ProfileSummaryResponse::name).toList());
        assertEquals(afterAssign, afterRemove, "Lo que devuelve es lo que Keycloak tenga, sin cálculo local");
        assertEquals(2, auditLines.list.size());
        assertTrue(auditLines.list.get(0).getFormattedMessage().contains("action=PROFILE_ASSIGNED"));
        assertTrue(auditLines.list.get(1).getFormattedMessage().contains("action=PROFILE_REMOVED"));
    }

    @Test
    void profileMembersAreTheHoldersOfThatRealmRoleAndOnlyOfAProfile() {
        when(keycloak.findRealmRole("mto-users-viewer")).thenReturn(role("p1", "mto-users-viewer"));
        when(keycloak.listRealmRoleMembers("mto-users-viewer", 0, 20)).thenReturn(List.of(user("usuarios.lector")));

        assertEquals(List.of("usuarios.lector"),
                profileService.listProfileMembers("mto-users-viewer", 0, 20).stream().map(UserResponse::username).toList());

        assertThrows(ProfileNotFoundException.class, () -> profileService.listProfileMembers("default-roles-mto", 0, 20));
        verify(keycloak, never()).listRealmRoleMembers(org.mockito.ArgumentMatchers.eq("default-roles-mto"), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
    }

    // --- helpers ----------------------------------------------------------------------------------

    private String onlyAuditLine() {
        assertEquals(1, auditLines.list.size(), "Exactamente una línea de auditoría");
        String line = auditLines.list.getFirst().getFormattedMessage();
        assertNotNull(line);
        return line;
    }

    private static KeycloakAdminProperties keycloakProperties() {
        return new KeycloakAdminProperties("http://kc:8080", "mto", "mto-users-svc", "secret",
                Duration.ofSeconds(2), Duration.ofSeconds(10), 10, List.of("realm-management", "broker"));
    }

    private static CredentialRepresentation credential(String id, String type, String label, long createdDate) {
        CredentialRepresentation credential = new CredentialRepresentation();
        credential.setId(id);
        credential.setType(type);
        credential.setUserLabel(label);
        credential.setCreatedDate(createdDate);
        // Lo que Keycloak si devuelve de como esta guardado el secreto, y que no puede salir por la API.
        credential.setCredentialData("{\"algorithm\":\"argon2\",\"hashIterations\":5}");
        return credential;
    }

    private static UserSessionRepresentation session(String id, String ip, Map<String, String> clients) {
        UserSessionRepresentation session = new UserSessionRepresentation();
        session.setId(id);
        session.setUsername("ana.uno");
        session.setIpAddress(ip);
        session.setStart(1_700_000_000_000L);
        session.setLastAccess(1_700_000_060_000L);
        session.setClients(clients);
        return session;
    }

    private static UserRepresentation user(String username) {
        UserRepresentation user = new UserRepresentation();
        user.setId(USER_ID);
        user.setUsername(username);
        return user;
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

    private static RoleRepresentation clientRole(String clientUuid, String name) {
        RoleRepresentation role = role(clientUuid + ":" + name, name);
        role.setClientRole(true);
        role.setContainerId(clientUuid);
        return role;
    }

    private static ClientMappingsRepresentation clientMappings(String clientId, String... roleNames) {
        ClientMappingsRepresentation mappings = new ClientMappingsRepresentation();
        mappings.setClient(clientId);
        mappings.setMappings(java.util.Arrays.stream(roleNames).map(name -> role("id-" + name, name)).toList());
        return mappings;
    }
}
