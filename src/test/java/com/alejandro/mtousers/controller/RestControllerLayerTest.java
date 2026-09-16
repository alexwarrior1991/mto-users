package com.alejandro.mtousers.controller;

import com.alejandro.mtousers.configuration.security.RestAccessDeniedHandler;
import com.alejandro.mtousers.configuration.security.RestAuthenticationEntryPoint;
import com.alejandro.mtousers.configuration.security.SecurityAuthorityPrefixes;
import com.alejandro.mtousers.configuration.security.SecurityConfiguration;
import com.alejandro.mtousers.configuration.security.SecurityRoles;
import com.alejandro.mtousers.dto.ClientRoleAssignment;
import com.alejandro.mtousers.dto.CreateUserRequest;
import com.alejandro.mtousers.dto.PageResponse;
import com.alejandro.mtousers.dto.ProfileSummaryResponse;
import com.alejandro.mtousers.dto.RequiredAction;
import com.alejandro.mtousers.dto.ResetPasswordRequest;
import com.alejandro.mtousers.dto.RoleNamesRequest;
import com.alejandro.mtousers.dto.UserResponse;
import com.alejandro.mtousers.dto.UserRolesResponse;
import com.alejandro.mtousers.dto.UserSearchCriteria;
import com.alejandro.mtousers.dto.UserSessionResponse;
import com.alejandro.mtousers.exception.GlobalExceptionHandler;
import com.alejandro.mtousers.exception.InvalidSearchException;
import com.alejandro.mtousers.exception.KeycloakUnavailableException;
import com.alejandro.mtousers.exception.SessionNotFoundException;
import com.alejandro.mtousers.exception.UserAlreadyExistsException;
import com.alejandro.mtousers.exception.UserNotFoundException;
import com.alejandro.mtousers.service.ProfileService;
import com.alejandro.mtousers.service.RoleService;
import com.alejandro.mtousers.service.UserService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasItems;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * El contrato HTTP de los controladores: rutas, códigos, cuerpos JSON, validación y el formato
 * {@code application/problem+json} de los errores. Los servicios son dobles; la seguridad es la
 * real, con un token que lo puede todo.
 */
@WebMvcTest(controllers = {UserController.class, RoleController.class, ProfileController.class})
@AutoConfigureMockMvc
@Import({SecurityConfiguration.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = {
        "app.security.client-id=mto-users-api",
        "app.security.principal-claim=preferred_username",
        "app.security.audience-validation-enabled=false",
        "app.security.expose-api-docs=false",
        "app.security.cors.allowed-origins=http://localhost:4200",
        "app.security.cors.allowed-methods=GET,POST,PUT,PATCH,DELETE",
        "app.security.cors.allowed-headers=Authorization,Content-Type",
        "app.security.cors.allow-credentials=false",
        "app.security.cors.max-age=3600",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:8082/realms/mto"
})
class RestControllerLayerTest {

    private static final String USERS = "/api/v1/users";
    private static final String USER_ID = "2f1c9d1e-0000-4000-8000-000000000001";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private RoleService roleService;

    @MockitoBean
    private ProfileService profileService;

    @Test
    void searchAnswersThePageAndDefaultsPagination() throws Exception {
        when(userService.search(any())).thenReturn(new PageResponse<>(List.of(user("ana.uno")), 0, 20, 1));

        mockMvc.perform(get(USERS).param("search", "ana").param("enabled", "true").with(admin()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.content[0].username").value("ana.uno"))
                .andExpect(jsonPath("$.content[0].createdAt").value("1970-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(header().exists("X-Correlation-Id"));

        verify(userService).search(new UserSearchCriteria("ana", null, null, true, null, List.of(), 0, 20));
    }

    @Test
    void attributeFiltersTravelAsARepeatedParameter() throws Exception {
        when(userService.search(any())).thenReturn(new PageResponse<>(List.of(user("ops.uno")), 0, 20, 1));

        mockMvc.perform(get(USERS).param("attribute", "departamento:operaciones").param("attribute", "turno:noche").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].username").value("ops.uno"));

        verify(userService).search(new UserSearchCriteria(null, null, null, null, null,
                List.of("departamento:operaciones", "turno:noche"), 0, 20));
    }

    @Test
    void anAttributeThatIsNotKeyValueIsRejectedBeforeReachingKeycloak() throws Exception {
        mockMvc.perform(get(USERS).param("attribute", "sin-dos-puntos").with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQ-VALIDATION"));

        verify(userService, never()).search(any());
    }

    @Test
    void combiningTextSearchAndAttributeAnswers400WithItsOwnCode() throws Exception {
        when(userService.search(any())).thenThrow(new InvalidSearchException("'search' and 'attribute' cannot be combined"));

        mockMvc.perform(get(USERS).param("search", "ana").param("attribute", "departamento:ops").with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("SEARCH-400"));
    }

    @Test
    void sessionsAreListedAndClosed() throws Exception {
        when(userService.listSessions(USER_ID)).thenReturn(List.of(new UserSessionResponse(
                "session-1", "ana.uno", "10.0.0.9", Instant.EPOCH, Instant.EPOCH.plusSeconds(60), List.of("mto-frontend"))));

        mockMvc.perform(get(USERS + "/" + USER_ID + "/sessions").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("session-1"))
                .andExpect(jsonPath("$[0].ipAddress").value("10.0.0.9"))
                .andExpect(jsonPath("$[0].startedAt").value("1970-01-01T00:00:00Z"))
                .andExpect(jsonPath("$[0].clients[0]").value("mto-frontend"));

        mockMvc.perform(delete(USERS + "/" + USER_ID + "/sessions/session-1").with(admin()))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete(USERS + "/" + USER_ID + "/sessions").with(admin()))
                .andExpect(status().isNoContent());

        verify(userService).revokeSession(USER_ID, "session-1");
        verify(userService).revokeAllSessions(USER_ID);
    }

    @Test
    void aSessionOfAnotherUserAnswers404ProblemJson() throws Exception {
        doThrow(new SessionNotFoundException("otra")).when(userService).revokeSession(USER_ID, "otra");

        mockMvc.perform(delete(USERS + "/" + USER_ID + "/sessions/otra").with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("SES-404"));
    }

    @Test
    void theReverseLookupsAnswerAPlainListWithOffsetPagination() throws Exception {
        when(profileService.listProfileMembers("mto-users-admin", 0, 20)).thenReturn(List.of(user("usuarios.responsable")));
        when(roleService.listClientRoleMembers("mto-stock-api", "stock-read", 10, 5)).thenReturn(List.of(user("almacen.lector")));

        mockMvc.perform(get(USERS + "/profiles/mto-users-admin/users").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].username").value("usuarios.responsable"));
        mockMvc.perform(get(USERS + "/roles/clients/mto-stock-api/stock-read/users")
                        .param("first", "10").param("max", "5").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].username").value("almacen.lector"));
    }

    @Test
    void searchRejectsAPageSizeAbove200AsProblemJson() throws Exception {
        mockMvc.perform(get(USERS).param("max", "500").with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("REQ-VALIDATION"))
                .andExpect(jsonPath("$.validationErrors[0].field").value("max"));
    }

    @Test
    void createAnswers201WithLocationAndTheParsedRequest() throws Exception {
        when(userService.create(any())).thenReturn(user("ana.nueva"));

        mockMvc.perform(post(USERS).with(admin()).contentType(MediaType.APPLICATION_JSON).content("""
                        {"username":"ana.nueva","firstName":"Ana","email":"ana@mto.local","temporaryPassword":"Secreta.123",
                         "requiredActions":["UPDATE_PASSWORD"],"attributes":{"dept":["ops"]}}
                        """))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, endsWith(USERS + "/" + USER_ID)))
                .andExpect(jsonPath("$.id").value(USER_ID))
                .andExpect(jsonPath("$.username").value("ana.nueva"));

        ArgumentCaptor<CreateUserRequest> request = ArgumentCaptor.forClass(CreateUserRequest.class);
        verify(userService).create(request.capture());
        assertEquals("ana.nueva", request.getValue().username());
        assertEquals("Secreta.123", request.getValue().temporaryPassword());
        assertEquals(List.of(RequiredAction.UPDATE_PASSWORD), request.getValue().requiredActions());
        assertEquals(List.of("ops"), request.getValue().attributes().get("dept"));
    }

    @Test
    void createWithAnInvalidBodyAnswers400ProblemJsonWithTheFields() throws Exception {
        mockMvc.perform(post(USERS).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .header("X-Correlation-Id", "corr-42")
                        .content("{\"username\":\" \",\"email\":\"nope\",\"temporaryPassword\":\"corta\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:problem:mto-users:REQ-VALIDATION"))
                .andExpect(jsonPath("$.errorCode").value("REQ-VALIDATION"))
                .andExpect(jsonPath("$.correlationId").value("corr-42"))
                .andExpect(jsonPath("$.validationErrors[*].field").value(hasItems("username", "email", "temporaryPassword")))
                .andExpect(header().string("X-Correlation-Id", "corr-42"));

        verify(userService, never()).create(any());
    }

    @Test
    void malformedJsonAnswers400() throws Exception {
        mockMvc.perform(post(USERS).with(admin()).contentType(MediaType.APPLICATION_JSON).content("{nope"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQ-400"));
    }

    @Test
    void anUnknownRequiredActionAnswers400() throws Exception {
        mockMvc.perform(post(USERS + "/" + USER_ID + "/execute-actions-email").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"actions\":[\"DELETE_EVERYTHING\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQ-400"));
    }

    @Test
    void businessErrorsTravelAsProblemJson() throws Exception {
        when(userService.get(USER_ID)).thenThrow(new UserNotFoundException(USER_ID));
        when(userService.create(any())).thenThrow(new UserAlreadyExistsException("User exists with same username"));
        when(profileService.listProfiles()).thenThrow(new KeycloakUnavailableException("Keycloak did not answer", null));

        mockMvc.perform(get(USERS + "/" + USER_ID).with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("USR-404"))
                .andExpect(jsonPath("$.instance").value(USERS + "/" + USER_ID));
        mockMvc.perform(post(USERS).with(admin()).contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"ana\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("USR-409"))
                .andExpect(jsonPath("$.detail").value("User exists with same username"));
        mockMvc.perform(get(USERS + "/profiles").with(admin()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "10"))
                .andExpect(jsonPath("$.errorCode").value("KC-503"));
    }

    @Test
    void anImpossibleUserIdIsRejectedBeforeReachingKeycloak() throws Exception {
        mockMvc.perform(get(USERS + "/{id}", "not$valid").with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQ-VALIDATION"));

        verify(userService, never()).get(any());
    }

    /** {@code profiles} y {@code roles} son segmentos literales: ganan a {@code {userId}}. */
    @Test
    void theCataloguesAreNotMistakenForUserIds() throws Exception {
        when(profileService.listProfiles()).thenReturn(List.of(new ProfileSummaryResponse("mto-users-admin", "Admin")));
        when(roleService.listClients()).thenReturn(List.of());

        mockMvc.perform(get(USERS + "/profiles").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("mto-users-admin"));
        mockMvc.perform(get(USERS + "/roles/clients").with(admin())).andExpect(status().isOk());

        verify(userService, never()).get(any());
    }

    @Test
    void removingClientRolesTakesTheNamesInTheBodyOfTheDelete() throws Exception {
        when(roleService.removeClientRoles(eq(USER_ID), eq("mto-stock-api"), any()))
                .thenReturn(new UserRolesResponse(List.of(), List.of(new ClientRoleAssignment("mto-stock-api", List.of("stock-read")))));

        mockMvc.perform(delete(USERS + "/" + USER_ID + "/roles/clients/mto-stock-api").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"roles\":[\"stock-write\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientRoles[0].clientId").value("mto-stock-api"))
                .andExpect(jsonPath("$.clientRoles[0].roles[0]").value("stock-read"));

        verify(roleService).removeClientRoles(USER_ID, "mto-stock-api", new RoleNamesRequest(List.of("stock-write")));
    }

    @Test
    void resetPasswordAnswers204AndPassesTheRequest() throws Exception {
        mockMvc.perform(post(USERS + "/" + USER_ID + "/reset-password").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"Secreta.123\",\"temporary\":false}"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(userService).resetPassword(USER_ID, new ResetPasswordRequest("Secreta.123", false));
    }

    @Test
    void assigningAProfileAnswersTheProfilesOfTheUser() throws Exception {
        when(profileService.assignProfile(USER_ID, "mto-users-viewer"))
                .thenReturn(List.of(new ProfileSummaryResponse("mto-users-viewer", "Consulta")));

        mockMvc.perform(put(USERS + "/" + USER_ID + "/profiles/mto-users-viewer").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("mto-users-viewer"));
        mockMvc.perform(delete(USERS + "/" + USER_ID + "/profiles/mto-users-viewer").with(admin()))
                .andExpect(status().isOk());

        verify(profileService).removeProfile(USER_ID, "mto-users-viewer");
    }

    private static UserResponse user(String username) {
        return new UserResponse(USER_ID, username, null, null, username + "@mto.local", false, true, Instant.EPOCH, Map.of(), List.of());
    }

    private static RequestPostProcessor admin() {
        return jwt().authorities(AuthorityUtils.createAuthorityList(
                SecurityAuthorityPrefixes.ROLE_PREFIX + SecurityRoles.USERS_READ,
                SecurityAuthorityPrefixes.ROLE_PREFIX + SecurityRoles.USERS_WRITE,
                SecurityAuthorityPrefixes.ROLE_PREFIX + SecurityRoles.USERS_DELETE,
                SecurityAuthorityPrefixes.ROLE_PREFIX + SecurityRoles.USERS_ROLES_WRITE,
                SecurityAuthorityPrefixes.ROLE_PREFIX + SecurityRoles.USERS_PASSWORD_RESET,
                SecurityAuthorityPrefixes.ROLE_PREFIX + SecurityRoles.USERS_PROFILES_WRITE,
                SecurityAuthorityPrefixes.ROLE_PREFIX + SecurityRoles.USERS_SESSIONS_WRITE));
    }
}
