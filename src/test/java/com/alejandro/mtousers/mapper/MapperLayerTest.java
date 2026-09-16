package com.alejandro.mtousers.mapper;

import com.alejandro.mtousers.dto.ClientRoleAssignment;
import com.alejandro.mtousers.dto.CreateUserRequest;
import com.alejandro.mtousers.dto.RequiredAction;
import com.alejandro.mtousers.dto.UpdateUserRequest;
import com.alejandro.mtousers.dto.UserResponse;
import com.alejandro.mtousers.dto.UserRolesResponse;
import com.alejandro.mtousers.dto.UserSessionResponse;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.ClientMappingsRepresentation;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.representations.idm.UserSessionRepresentation;
import org.mapstruct.factory.Mappers;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapperLayerTest {

    private final UserMapper userMapper = Mappers.getMapper(UserMapper.class);
    private final RoleMapper roleMapper = Mappers.getMapper(RoleMapper.class);
    private final ProfileMapper profileMapper = Mappers.getMapper(ProfileMapper.class);

    @Test
    void aUserRepresentationBecomesAResponseWithoutCredentials() {
        UserRepresentation representation = new UserRepresentation();
        representation.setId("id-1");
        representation.setUsername("ana.uno");
        representation.setFirstName("Ana");
        representation.setEmail("ana@mto.local");
        representation.setEmailVerified(true);
        representation.setEnabled(false);
        representation.setCreatedTimestamp(1_700_000_000_000L);
        representation.setAttributes(Map.of("dept", List.of("ops")));
        representation.setRequiredActions(List.of("UPDATE_PASSWORD"));
        CredentialRepresentation credential = new CredentialRepresentation();
        credential.setValue("Secreta.123");
        representation.setCredentials(List.of(credential));

        UserResponse response = userMapper.toResponse(representation);

        assertEquals("id-1", response.id());
        assertEquals("ana.uno", response.username());
        assertEquals("Ana", response.firstName());
        assertNull(response.lastName());
        assertEquals(Boolean.TRUE, response.emailVerified());
        assertEquals(Boolean.FALSE, response.enabled());
        assertEquals(Instant.ofEpochMilli(1_700_000_000_000L), response.createdAt());
        assertEquals(List.of("ops"), response.attributes().get("dept"));
        assertEquals(List.of("UPDATE_PASSWORD"), response.requiredActions());
        assertTrue(response.toString().contains("ana.uno"));
        assertTrue(!response.toString().contains("Secreta.123"), "La respuesta no tiene por dónde llevar una credencial");
    }

    @Test
    void aCreateRequestBecomesARepresentationWithOnlyTheBasicFieldsAndTheTemporaryPassword() {
        UserRepresentation representation = userMapper.toRepresentation(new CreateUserRequest("ana.nueva", "Ana", null,
                "ana@mto.local", true, true, Map.of("dept", List.of("ops")),
                List.of(RequiredAction.VERIFY_EMAIL, RequiredAction.CONFIGURE_TOTP), "Secreta.123"));

        assertEquals("ana.nueva", representation.getUsername());
        assertEquals("Ana", representation.getFirstName());
        assertNull(representation.getLastName());
        assertEquals(Boolean.TRUE, representation.isEmailVerified());
        assertEquals(Boolean.TRUE, representation.isEnabled());
        assertEquals(List.of("ops"), representation.getAttributes().get("dept"));
        assertEquals(List.of("VERIFY_EMAIL", "CONFIGURE_TOTP"), representation.getRequiredActions());
        assertEquals(1, representation.getCredentials().size());
        assertEquals(CredentialRepresentation.PASSWORD, representation.getCredentials().getFirst().getType());
        assertEquals("Secreta.123", representation.getCredentials().getFirst().getValue());
        assertEquals(Boolean.TRUE, representation.getCredentials().getFirst().isTemporary());
        assertNull(representation.getId());
        assertNull(representation.getGroups());
    }

    @Test
    void aCreateRequestWithoutPasswordCarriesNoCredential() {
        assertNull(userMapper.toRepresentation(new CreateUserRequest("ana.nueva", null, null, null, null, null, null, null, null)).getCredentials());
    }

    @Test
    void anUpdateOnlyOverwritesWhatItBrings() {
        UserRepresentation representation = new UserRepresentation();
        representation.setId("id-1");
        representation.setUsername("ana.uno");
        representation.setFirstName("Ana");
        representation.setLastName("Uno");
        representation.setEmail("ana@mto.local");
        representation.setEnabled(true);

        userMapper.applyUpdate(new UpdateUserRequest(null, "Dos", "ana.dos@mto.local", null, Map.of("dept", List.of("ops"))), representation);

        assertEquals("id-1", representation.getId());
        assertEquals("ana.uno", representation.getUsername());
        assertEquals("Ana", representation.getFirstName());
        assertEquals("Dos", representation.getLastName());
        assertEquals("ana.dos@mto.local", representation.getEmail());
        assertEquals(Boolean.TRUE, representation.isEnabled());
        assertEquals(List.of("ops"), representation.getAttributes().get("dept"));
    }

    @Test
    void clientsAndRolesMapByName() {
        ClientRepresentation client = new ClientRepresentation();
        client.setId("uuid");
        client.setClientId("mto-stock-api");
        client.setName("API de stock MTO");
        client.setDescription("Resource server");
        RoleRepresentation role = new RoleRepresentation();
        role.setName("stock-read");
        role.setDescription("Consulta");
        role.setComposite(false);

        assertEquals("mto-stock-api", roleMapper.toClientResponse(client).clientId());
        assertEquals("API de stock MTO", roleMapper.toClientResponse(client).name());
        assertEquals("stock-read", roleMapper.toRoleResponse(role).name());
        assertEquals("Consulta", roleMapper.toRoleResponse(role).description());
        assertEquals(false, roleMapper.toRoleResponse(role).composite());
        assertEquals("mto-stock-api", profileMapper.toSummary(namedRole("mto-stock-api")).name());
    }

    @Test
    void userRoleMappingsBecomeASortedListPerClient() {
        ClientMappingsRepresentation stock = new ClientMappingsRepresentation();
        stock.setClient("mto-stock-api");
        stock.setMappings(List.of(namedRole("stock-write"), namedRole("stock-read")));
        ClientMappingsRepresentation empty = new ClientMappingsRepresentation();
        empty.setClient("mto-users-api");

        UserRolesResponse response = roleMapper.toUserRolesResponse(List.of(namedRole("mto-viewer"), namedRole("default-roles-mto")),
                Map.of("mto-users-api", empty, "mto-stock-api", stock));

        assertEquals(List.of("default-roles-mto", "mto-viewer"), response.realmRoles());
        assertEquals(List.of(new ClientRoleAssignment("mto-stock-api", List.of("stock-read", "stock-write")),
                new ClientRoleAssignment("mto-users-api", List.of())), response.clientRoles());
        assertEquals(new UserRolesResponse(List.of(), List.of()), roleMapper.toUserRolesResponse(null, null));
    }

    @Test
    void aSessionKeepsOnlyTheClientNamesAndTurnsTheTimestampsIntoInstants() {
        UserSessionRepresentation session = new UserSessionRepresentation();
        session.setId("session-1");
        session.setUsername("ana.uno");
        session.setUserId("id-1");
        session.setIpAddress("10.0.0.9");
        session.setStart(1_700_000_000_000L);
        session.setLastAccess(1_700_000_060_000L);
        session.setClients(new java.util.LinkedHashMap<>(Map.of("uuid-b", "mto-users-api", "uuid-a", "mto-frontend")));

        UserSessionResponse response = userMapper.toSessionResponse(session);

        assertEquals("session-1", response.id());
        assertEquals("ana.uno", response.username());
        assertEquals("10.0.0.9", response.ipAddress());
        assertEquals(Instant.ofEpochMilli(1_700_000_000_000L), response.startedAt());
        assertEquals(Instant.ofEpochMilli(1_700_000_060_000L), response.lastAccessAt());
        assertEquals(List.of("mto-frontend", "mto-users-api"), response.clients(), "Ordenados y sin el UUID interno");
        assertTrue(userMapper.toSessionResponses(null).isEmpty());
        assertEquals(List.of(), userMapper.toSessionResponse(sessionWithoutClients()).clients());
    }

    private static UserSessionRepresentation sessionWithoutClients() {
        UserSessionRepresentation session = new UserSessionRepresentation();
        session.setId("session-2");
        return session;
    }

    private static RoleRepresentation namedRole(String name) {
        RoleRepresentation role = new RoleRepresentation();
        role.setName(name);
        return role;
    }
}
