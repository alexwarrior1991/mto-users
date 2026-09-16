package com.alejandro.mtousers.configuration.security;

import com.alejandro.mtousers.controller.ProfileController;
import com.alejandro.mtousers.controller.RoleController;
import com.alejandro.mtousers.controller.UserController;
import com.alejandro.mtousers.dto.UserResponse;
import com.alejandro.mtousers.exception.GlobalExceptionHandler;
import com.alejandro.mtousers.service.ProfileService;
import com.alejandro.mtousers.service.RoleService;
import com.alejandro.mtousers.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Lo que se fija aquí es que cada operación pida su permiso y, sobre todo, que los permisos no se
 * impliquen entre sí: dar de alta usuarios no da derecho a repartir roles, ni a fijar contraseñas,
 * ni a borrar. Se prueba contra los controladores reales con los servicios sustituidos por dobles:
 * lo que se verifica son las reglas de la cadena de filtros, no la lógica.
 */
@WebMvcTest(controllers = {UserController.class, RoleController.class, ProfileController.class})
@AutoConfigureMockMvc
@Import({SecurityConfiguration.class,
        RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class,
        GlobalExceptionHandler.class})
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
class ApiAuthorizationRulesTest {

    private static final String USERS = SecurityConfiguration.API;
    private static final String USER = USERS + "/2f1c9d1e-0000-4000-8000-000000000001";
    private static final String USER_JSON = "{\"username\":\"ana.nueva\",\"email\":\"ana@mto.local\"}";
    private static final String ROLES_JSON = "{\"roles\":[\"stock-read\"]}";

    /**
     * Se deja el {@code JwtDecoder} real, sin sustituir por un doble: así el contexto ejercita el
     * cableado del bean. No toca la red porque el JWK Set se descarga de forma perezosa, y
     * {@code jwt()} inyecta la autenticación ya resuelta.
     */
    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private RoleService roleService;

    @MockitoBean
    private ProfileService profileService;

    @BeforeEach
    void stubCreate() {
        // El alta construye la cabecera Location con el id de la respuesta: el doble tiene que devolver algo.
        when(userService.create(any())).thenReturn(new UserResponse("new-id", "ana.nueva", null, null, "ana@mto.local",
                false, true, Instant.EPOCH, Map.of(), List.of()));
    }

    @Test
    void theDecoderIsWiredFromTheResourceServerProperties() {
        assertNotNull(jwtDecoder);
    }

    @Nested
    class WithoutAuthentication {

        @Test
        void everyBusinessOperationAnswers401AsProblemJson() throws Exception {
            mockMvc.perform(get(USERS))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().exists("WWW-Authenticate"))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.errorCode").value("AUTH-401"))
                    .andExpect(jsonPath("$.status").value(401));
            mockMvc.perform(post(USERS).contentType(MediaType.APPLICATION_JSON).content(USER_JSON)).andExpect(status().isUnauthorized());
            mockMvc.perform(delete(USER)).andExpect(status().isUnauthorized());
            mockMvc.perform(get(USERS + "/profiles")).andExpect(status().isUnauthorized());
            mockMvc.perform(get(USERS + "/roles/clients")).andExpect(status().isUnauthorized());
            mockMvc.perform(get(USER + "/sessions")).andExpect(status().isUnauthorized());
            mockMvc.perform(delete(USER + "/sessions")).andExpect(status().isUnauthorized());
        }

        @Test
        void probesAndPreflightStayOpen() throws Exception {
            // 404 y no 401: la ruta está permitida; Actuator no forma parte de este slice.
            mockMvc.perform(get("/actuator/health")).andExpect(status().isNotFound());
            mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isNotFound());
            mockMvc.perform(get("/actuator/info")).andExpect(status().isNotFound());
            mockMvc.perform(options(USERS)
                            .header("Origin", "http://localhost:4200")
                            .header("Access-Control-Request-Method", "POST"))
                    .andExpect(status().isOk());
        }

        @Test
        void theRestOfActuatorNeedsAToken() throws Exception {
            mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
            mockMvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
        }

        @Test
        void theDocumentationIsClosedUnlessConfiguredOtherwise() throws Exception {
            mockMvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
            mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isUnauthorized());
        }
    }

    @Nested
    class WithReadRole {

        @Test
        void readingCoversTheReverseLookupsAndTheSessions() throws Exception {
            mockMvc.perform(get(USERS + "/profiles/mto-users-admin/users").with(role(SecurityRoles.USERS_READ))).andExpect(status().isOk());
            mockMvc.perform(get(USERS + "/roles/clients/mto-stock-api/stock-read/users").with(role(SecurityRoles.USERS_READ))).andExpect(status().isOk());
            mockMvc.perform(get(USER + "/sessions").with(role(SecurityRoles.USERS_READ))).andExpect(status().isOk());
        }

        @Test
        void readingSucceedsOnEveryCatalogueAndOnUsers() throws Exception {
            mockMvc.perform(get(USERS).with(role(SecurityRoles.USERS_READ))).andExpect(status().isOk());
            mockMvc.perform(get(USER).with(role(SecurityRoles.USERS_READ))).andExpect(status().isOk());
            mockMvc.perform(head(USER).with(role(SecurityRoles.USERS_READ))).andExpect(status().isOk());
            mockMvc.perform(get(USER + "/roles").with(role(SecurityRoles.USERS_READ))).andExpect(status().isOk());
            mockMvc.perform(get(USER + "/profiles").with(role(SecurityRoles.USERS_READ))).andExpect(status().isOk());
            mockMvc.perform(get(USERS + "/roles/clients").with(role(SecurityRoles.USERS_READ))).andExpect(status().isOk());
            mockMvc.perform(get(USERS + "/roles/clients/mto-stock-api").with(role(SecurityRoles.USERS_READ))).andExpect(status().isOk());
            mockMvc.perform(get(USERS + "/profiles").with(role(SecurityRoles.USERS_READ))).andExpect(status().isOk());
            mockMvc.perform(get(USERS + "/profiles/mto-users-admin").with(role(SecurityRoles.USERS_READ))).andExpect(status().isOk());
        }

        @Test
        void readingDoesNotGrantAnyWrite() throws Exception {
            mockMvc.perform(json(post(USERS), USER_JSON).with(role(SecurityRoles.USERS_READ)))
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.errorCode").value("AUTH-403"));
            mockMvc.perform(json(put(USER), "{}").with(role(SecurityRoles.USERS_READ))).andExpect(status().isForbidden());
            mockMvc.perform(json(patch(USER + "/enabled"), "{\"enabled\":false}").with(role(SecurityRoles.USERS_READ))).andExpect(status().isForbidden());
            mockMvc.perform(delete(USER).with(role(SecurityRoles.USERS_READ))).andExpect(status().isForbidden());
            mockMvc.perform(json(post(USER + "/reset-password"), "{\"password\":\"Secreta.123\"}").with(role(SecurityRoles.USERS_READ))).andExpect(status().isForbidden());
            mockMvc.perform(json(put(USER + "/roles/clients/mto-stock-api"), ROLES_JSON).with(role(SecurityRoles.USERS_READ))).andExpect(status().isForbidden());
            mockMvc.perform(put(USER + "/profiles/mto-users-viewer").with(role(SecurityRoles.USERS_READ))).andExpect(status().isForbidden());
            mockMvc.perform(delete(USER + "/sessions").with(role(SecurityRoles.USERS_READ))).andExpect(status().isForbidden());
            mockMvc.perform(delete(USER + "/sessions/abc").with(role(SecurityRoles.USERS_READ))).andExpect(status().isForbidden());
        }
    }

    @Nested
    class WithWriteRole {

        @Test
        void writingCoversCreateUpdateEnableAndActionsEmail() throws Exception {
            mockMvc.perform(json(post(USERS), USER_JSON).with(role(SecurityRoles.USERS_WRITE))).andExpect(status().isCreated());
            mockMvc.perform(json(put(USER), "{\"firstName\":\"Ana\"}").with(role(SecurityRoles.USERS_WRITE))).andExpect(status().isOk());
            mockMvc.perform(json(patch(USER + "/enabled"), "{\"enabled\":false}").with(role(SecurityRoles.USERS_WRITE))).andExpect(status().isOk());
            mockMvc.perform(json(post(USER + "/execute-actions-email"), "{\"actions\":[\"UPDATE_PASSWORD\"]}").with(role(SecurityRoles.USERS_WRITE)))
                    .andExpect(status().isAccepted());
        }

        @Test
        void writingDoesNotGrantReadingDeletingPasswordsRolesOrProfiles() throws Exception {
            mockMvc.perform(get(USER).with(role(SecurityRoles.USERS_WRITE))).andExpect(status().isForbidden());
            mockMvc.perform(delete(USER).with(role(SecurityRoles.USERS_WRITE))).andExpect(status().isForbidden());
            mockMvc.perform(json(post(USER + "/reset-password"), "{\"password\":\"Secreta.123\"}").with(role(SecurityRoles.USERS_WRITE))).andExpect(status().isForbidden());
            mockMvc.perform(json(put(USER + "/roles/clients/mto-stock-api"), ROLES_JSON).with(role(SecurityRoles.USERS_WRITE))).andExpect(status().isForbidden());
            mockMvc.perform(json(delete(USER + "/roles/clients/mto-stock-api"), ROLES_JSON).with(role(SecurityRoles.USERS_WRITE))).andExpect(status().isForbidden());
            mockMvc.perform(put(USER + "/profiles/mto-users-viewer").with(role(SecurityRoles.USERS_WRITE))).andExpect(status().isForbidden());
            mockMvc.perform(delete(USER + "/profiles/mto-users-viewer").with(role(SecurityRoles.USERS_WRITE))).andExpect(status().isForbidden());
            mockMvc.perform(delete(USER + "/sessions").with(role(SecurityRoles.USERS_WRITE))).andExpect(status().isForbidden());
        }
    }

    @Nested
    class WithTheSpecificRoles {

        @Test
        void deletingNeedsItsOwnRoleAndNothingElse() throws Exception {
            mockMvc.perform(delete(USER).with(role(SecurityRoles.USERS_DELETE))).andExpect(status().isNoContent());
            mockMvc.perform(get(USER).with(role(SecurityRoles.USERS_DELETE))).andExpect(status().isForbidden());
            mockMvc.perform(json(post(USERS), USER_JSON).with(role(SecurityRoles.USERS_DELETE))).andExpect(status().isForbidden());
        }

        @Test
        void resettingPasswordsNeedsItsOwnRole() throws Exception {
            mockMvc.perform(json(post(USER + "/reset-password"), "{\"password\":\"Secreta.123\"}").with(role(SecurityRoles.USERS_PASSWORD_RESET)))
                    .andExpect(status().isNoContent());
            mockMvc.perform(json(post(USER + "/execute-actions-email"), "{\"actions\":[\"UPDATE_PASSWORD\"]}").with(role(SecurityRoles.USERS_PASSWORD_RESET)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(json(post(USERS), USER_JSON).with(role(SecurityRoles.USERS_PASSWORD_RESET))).andExpect(status().isForbidden());
        }

        @Test
        void assigningClientRolesNeedsItsOwnRoleAndDoesNotReachProfiles() throws Exception {
            mockMvc.perform(json(put(USER + "/roles/clients/mto-stock-api"), ROLES_JSON).with(role(SecurityRoles.USERS_ROLES_WRITE))).andExpect(status().isOk());
            mockMvc.perform(json(delete(USER + "/roles/clients/mto-stock-api"), ROLES_JSON).with(role(SecurityRoles.USERS_ROLES_WRITE))).andExpect(status().isOk());
            mockMvc.perform(put(USER + "/profiles/mto-users-viewer").with(role(SecurityRoles.USERS_ROLES_WRITE))).andExpect(status().isForbidden());
            mockMvc.perform(json(post(USERS), USER_JSON).with(role(SecurityRoles.USERS_ROLES_WRITE))).andExpect(status().isForbidden());
            mockMvc.perform(delete(USER).with(role(SecurityRoles.USERS_ROLES_WRITE))).andExpect(status().isForbidden());
        }

        @Test
        void assigningProfilesNeedsItsOwnRoleAndDoesNotReachClientRoles() throws Exception {
            mockMvc.perform(put(USER + "/profiles/mto-users-viewer").with(role(SecurityRoles.USERS_PROFILES_WRITE))).andExpect(status().isOk());
            mockMvc.perform(delete(USER + "/profiles/mto-users-viewer").with(role(SecurityRoles.USERS_PROFILES_WRITE))).andExpect(status().isOk());
            mockMvc.perform(json(put(USER + "/roles/clients/mto-stock-api"), ROLES_JSON).with(role(SecurityRoles.USERS_PROFILES_WRITE))).andExpect(status().isForbidden());
            mockMvc.perform(delete(USER).with(role(SecurityRoles.USERS_PROFILES_WRITE))).andExpect(status().isForbidden());
        }

        /**
         * Cerrar sesiones expulsa a alguien que esta trabajando, asi que va aparte: ni el borrado de
         * usuarios ni la escritura ordinaria lo conceden, y el permiso no abre ninguna otra puerta.
         */
        @Test
        void closingSessionsNeedsItsOwnRoleAndGrantsNothingElse() throws Exception {
            mockMvc.perform(delete(USER + "/sessions").with(role(SecurityRoles.USERS_SESSIONS_WRITE))).andExpect(status().isNoContent());
            mockMvc.perform(delete(USER + "/sessions/abc").with(role(SecurityRoles.USERS_SESSIONS_WRITE))).andExpect(status().isNoContent());

            mockMvc.perform(delete(USER + "/sessions").with(role(SecurityRoles.USERS_DELETE))).andExpect(status().isForbidden());
            mockMvc.perform(get(USER + "/sessions").with(role(SecurityRoles.USERS_SESSIONS_WRITE))).andExpect(status().isForbidden());
            mockMvc.perform(delete(USER).with(role(SecurityRoles.USERS_SESSIONS_WRITE))).andExpect(status().isForbidden());
            mockMvc.perform(json(post(USERS), USER_JSON).with(role(SecurityRoles.USERS_SESSIONS_WRITE))).andExpect(status().isForbidden());
        }

        /**
         * Un rol de realm con el mismo nombre que un permiso no lo concede: llega como
         * {@code ROLE_REALM_USERS_DELETE}, que ninguna regla comprueba.
         */
        @Test
        void aRealmRoleNamedLikeAPermissionDoesNotGrantIt() throws Exception {
            mockMvc.perform(delete(USER).with(jwt().authorities(AuthorityUtils.createAuthorityList("ROLE_REALM_USERS_DELETE"))))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    class WithOperationsRoles {

        @Test
        void metricsReaderCannotTouchTheBusinessApi() throws Exception {
            mockMvc.perform(get(USER).with(role(SecurityRoles.OPS_METRICS))).andExpect(status().isForbidden());
        }

        @Test
        void readingActuatorDoesNotGrantWritingToActuator() throws Exception {
            // 404 y no 403: el permiso pasa, el endpoint no existe en este slice.
            mockMvc.perform(get("/actuator/prometheus").with(role(SecurityRoles.OPS_METRICS))).andExpect(status().isNotFound());
            mockMvc.perform(post("/actuator/loggers/root").with(role(SecurityRoles.OPS_METRICS))).andExpect(status().isForbidden());
            mockMvc.perform(post("/actuator/loggers/root").with(role(SecurityRoles.OPS_WRITE))).andExpect(status().isNotFound());
        }
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, String body) {
        return builder.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static RequestPostProcessor role(String... roles) {
        String[] authorities = new String[roles.length];
        for (int index = 0; index < roles.length; index++) {
            authorities[index] = SecurityAuthorityPrefixes.ROLE_PREFIX + roles[index];
        }
        return jwt().authorities(AuthorityUtils.createAuthorityList(authorities));
    }
}
