package com.alejandro.mtousers.keycloak;

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
import com.alejandro.mtousers.exception.KeycloakUpstreamException;
import com.alejandro.mtousers.exception.ProfileNotFoundException;
import com.alejandro.mtousers.exception.ProtectedClientException;
import com.alejandro.mtousers.exception.RoleNotFoundException;
import com.alejandro.mtousers.exception.SessionNotFoundException;
import com.alejandro.mtousers.exception.UserAlreadyExistsException;
import com.alejandro.mtousers.exception.UserNotFoundException;
import com.alejandro.mtousers.service.ProfileService;
import com.alejandro.mtousers.service.RoleService;
import com.alejandro.mtousers.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Los tres servicios contra un Keycloak real: la cuenta de servicio con sus roles de
 * {@code realm-management}, la Admin API de verdad y la expansión de los perfiles en el token.
 *
 * <p>Lo que este test añade a los unitarios es lo que aquellos no pueden ver: que los seis roles de
 * {@code realm-management} de {@code mto-users-svc} bastan para todo lo que la API hace (si faltara
 * uno, aquí saldría un 403 de Keycloak traducido a {@code KeycloakAccessException}), y que asignar
 * un perfil —un rol compuesto de realm— hace que el token del usuario traiga los roles de cliente
 * de ese perfil. El realm de {@code src/test/resources/keycloak/mto-users-test-realm.json} tiene la
 * misma forma que el de {@code keycloak/}, así que sirve además de ejemplo ejecutable.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class KeycloakUsersIT {

    private static final String REALM = "mto";
    private static final int HTTP_PORT = 8080;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Container
    static final GenericContainer<?> KEYCLOAK =
            new GenericContainer<>(DockerImageName.parse("quay.io/keycloak/keycloak:26.1"))
                    .withExposedPorts(HTTP_PORT)
                    .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
                    .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
                    // El nombre dentro del contenedor tiene que ser <realm>-realm.json: un fichero cuyo
                    // nombre contiene "-realm.json" va por DirImportProvider, que saca el nombre del
                    // realm del nombre del fichero y lo vincula a la sesion en la ultima transaccion
                    // (la que inicializa las cuentas de servicio). Con "mto-users-test-realm.json"
                    // buscaba un realm "mto-users-test", no lo encontraba y la importacion moria con
                    // "Session not bound to a realm" al cachear el usuario de mto-users-svc.
                    .withCopyFileToContainer(
                            MountableFile.forClasspathResource("keycloak/mto-users-test-realm.json"),
                            "/opt/keycloak/data/import/" + REALM + "-realm.json")
                    .withCommand("start-dev", "--import-realm")
                    .waitingFor(Wait.forHttp("/realms/" + REALM + "/.well-known/openid-configuration")
                            .forPort(HTTP_PORT)
                            .forStatusCode(200)
                            .withStartupTimeout(Duration.ofMinutes(5)));

    @DynamicPropertySource
    static void keycloakProperties(DynamicPropertyRegistry registry) {
        registry.add("app.keycloak.auth-server-url", KeycloakUsersIT::serverUrl);
        registry.add("app.keycloak.realm", () -> REALM);
        registry.add("app.keycloak.admin-client-id", () -> "mto-users-svc");
        registry.add("app.keycloak.admin-client-secret", () -> "mto-users-svc-secret");
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> serverUrl() + "/realms/" + REALM);
    }

    @Autowired
    private UserService userService;

    @Autowired
    private RoleService roleService;

    @Autowired
    private ProfileService profileService;

    @Test
    void theWholeLifecycleOfAUserAgainstARealKeycloak() throws Exception {
        String username = "it." + UUID.randomUUID().toString().substring(0, 8);

        // Alta, lectura y búsqueda.
        UserResponse created = userService.create(new CreateUserRequest(username, "Ines", "Test", username + "@mto.local",
                true, null, Map.of("dept", List.of("ops")), null, null));
        assertNotNull(created.id());
        assertEquals(username, created.username());
        assertEquals(Boolean.TRUE, created.enabled());
        assertEquals(List.of("ops"), created.attributes().get("dept"));
        assertEquals(1, userService.search(new UserSearchCriteria(null, username, null, true, null, List.of(), 0, 10)).total());
        assertEquals(0, userService.search(new UserSearchCriteria(null, username, null, false, null, List.of(), 0, 10)).total(),
                "enabled se combina con el resto de filtros en la misma llamada");

        // Duplicado.
        assertThrows(UserAlreadyExistsException.class,
                () -> userService.create(new CreateUserRequest(username, null, null, null, null, null, null, null, null)));

        // Modificación parcial.
        UserResponse updated = userService.update(created.id(), new UpdateUserRequest(null, "Probada", null, null, null));
        assertEquals("Ines", updated.firstName());
        assertEquals("Probada", updated.lastName());

        // Perfil (rol compuesto de realm) y rol de cliente de otro servicio.
        profileService.assignProfile(created.id(), "mto-users-viewer");
        roleService.addClientRoles(created.id(), "mto-stock-api", new RoleNamesRequest(List.of("stock-write")));
        UserRolesResponse roles = roleService.getUserRoles(created.id());
        assertTrue(roles.realmRoles().contains("mto-users-viewer"));
        assertTrue(roles.clientRoles().contains(new ClientRoleAssignment("mto-stock-api", List.of("stock-write"))));
        assertEquals(List.of("mto-users-viewer"), profileService.getUserProfiles(created.id()).stream().map(ProfileSummaryResponse::name).toList());

        // El token del usuario trae el perfil expandido en roles de cliente: es lo que sostiene el modelo.
        userService.resetPassword(created.id(), new ResetPasswordRequest("Secreta.123", false));
        JsonNode token = decode(tokenFor(username, "Secreta.123"));
        assertTrue(rolesOf(token, "mto-users-api").contains("users-read"), "users-read llega por el perfil mto-users-viewer");
        assertTrue(rolesOf(token, "mto-stock-api").contains("stock-write"));
        assertTrue(token.path("realm_access").path("roles").toString().contains("mto-users-viewer"));

        // Quitar el perfil quita exactamente lo que dio.
        profileService.removeProfile(created.id(), "mto-users-viewer");
        assertTrue(profileService.getUserProfiles(created.id()).isEmpty());
        assertTrue(roleService.getUserRoles(created.id()).clientRoles().contains(new ClientRoleAssignment("mto-stock-api", List.of("stock-write"))));
        JsonNode tokenAfter = decode(tokenFor(username, "Secreta.123"));
        assertFalse(rolesOf(tokenAfter, "mto-users-api").contains("users-read"));

        // Deshabilitar, borrar.
        assertEquals(Boolean.FALSE, userService.setEnabled(created.id(), false).enabled());
        userService.delete(created.id());
        assertThrows(UserNotFoundException.class, () -> userService.get(created.id()));
    }

    @Test
    void theCataloguesComeFromTheRealm() {
        List<String> clients = roleService.listClients().stream().map(client -> client.clientId()).toList();
        assertTrue(clients.contains("mto-users-api"));
        assertTrue(clients.contains("mto-stock-api"));
        assertFalse(clients.contains("realm-management"), "Los clientes protegidos no existen para la API");

        assertTrue(roleService.listClientRoles("mto-users-api").stream().anyMatch(role -> role.name().equals("users-delete")));

        List<String> profiles = profileService.listProfiles().stream().map(ProfileSummaryResponse::name).toList();
        assertEquals(List.of("mto-users-admin", "mto-users-manager", "mto-users-viewer", "mto-warehouse-viewer"), profiles);

        ProfileResponse manager = profileService.getProfile("mto-users-manager");
        assertEquals("mto-users-api", manager.clientRoles().getFirst().clientId());
        assertTrue(manager.clientRoles().getFirst().roles().containsAll(List.of("users-read", "users-write", "users-roles-write")));
    }

    @Test
    void theSafeguardsHoldAgainstTheRealThing() {
        String existing = userService.search(new UserSearchCriteria(null, "existente", null, null, null, List.of(), 0, 1)).content().getFirst().id();

        assertThrows(ProfileNotFoundException.class, () -> profileService.assignProfile(existing, "plain-role"));
        assertThrows(ProtectedClientException.class,
                () -> roleService.addClientRoles(existing, "realm-management", new RoleNamesRequest(List.of("realm-admin"))));
        assertThrows(RoleNotFoundException.class,
                () -> roleService.addClientRoles(existing, "mto-stock-api", new RoleNamesRequest(List.of("stock-fly"))));
        assertThrows(UserNotFoundException.class, () -> userService.get(UUID.randomUUID().toString()));
    }

    /** Sin SMTP en el realm Keycloak responde 500: tiene que llegar como 502, no como 500 propio. */
    @Test
    void anEmailThatKeycloakCannotSendIsAnUpstreamError() {
        String existing = userService.search(new UserSearchCriteria(null, "existente", null, null, null, List.of(), 0, 1)).content().getFirst().id();

        KeycloakUpstreamException failure = assertThrows(KeycloakUpstreamException.class, () -> userService.executeActionsEmail(existing,
                new ExecuteActionsEmailRequest(List.of(RequiredAction.UPDATE_PASSWORD), null, null, null)));
        assertTrue(failure.getMessage().contains("HTTP 500"));
    }

    /**
     * La búsqueda por atributo viaja en el parámetro {@code q} de Keycloak, cuenta igual que lista y
     * combina con Y las claves distintas. Las dos formas en que {@code q} se pierde en silencio —ir
     * acompañado de {@code search}, o repetir una clave— se rechazan antes de llamar al servidor.
     */
    @Test
    void theAttributeSearchFiltersByTheUsersOwnAttributes() {
        PageResponse<UserResponse> taller = userService.search(byAttributes("departamento:taller"));
        assertEquals(List.of("existente"), usernamesOf(taller.content()));
        assertEquals(1, taller.total(), "El total se cuenta con el atributo, no sobre el realm entero");

        assertEquals(List.of("consultor"), usernamesOf(userService.search(byAttributes("departamento:operaciones")).content()));
        assertEquals(List.of("consultor"), usernamesOf(userService.search(byAttributes("departamento:operaciones", "turno:noche")).content()),
                "Dos claves distintas se combinan con Y");
        assertEquals(0, userService.search(byAttributes("departamento:taller", "turno:noche")).total(),
                "Nadie es del taller y del turno de noche a la vez");
        assertEquals(0, userService.search(byAttributes("departamento:mantenimiento")).total());

        assertThrows(InvalidSearchException.class, () -> userService.search(
                new UserSearchCriteria("existente", null, null, null, null, List.of("departamento:taller"), 0, 10)));
        assertThrows(InvalidSearchException.class,
                () -> userService.search(byAttributes("departamento:taller", "departamento:operaciones")),
                "Repetir la clave se rechaza: Keycloak se quedaria solo con el ultimo par");
    }

    /**
     * La búsqueda inversa responde a «quién tiene esto». Keycloak devuelve <b>solo asignaciones
     * directas</b>: quien llega a un rol de cliente a través de un perfil aparece en la lista del
     * perfil, nunca en la del rol.
     */
    @Test
    void theReverseLookupListsWhoHoldsAProfileAndWhoHoldsAClientRoleDirectly() {
        assertTrue(usernamesOf(profileService.listProfileMembers("mto-users-viewer", 0, 20))
                .containsAll(List.of("consultor", "existente")));
        assertTrue(profileService.listProfileMembers("mto-warehouse-viewer", 0, 20).isEmpty());
        assertThrows(ProfileNotFoundException.class, () -> profileService.listProfileMembers("plain-role", 0, 20));

        String username = "rev." + UUID.randomUUID().toString().substring(0, 8);
        UserResponse user = userService.create(new CreateUserRequest(username, null, null, null, null, true, null, null, null));

        profileService.assignProfile(user.id(), "mto-warehouse-viewer");
        assertEquals(List.of(username), usernamesOf(profileService.listProfileMembers("mto-warehouse-viewer", 0, 20)));
        assertTrue(roleService.listClientRoleMembers("mto-stock-api", "stock-read", 0, 20).isEmpty(),
                "El perfil le da stock-read, pero la lista del rol no expande los compuestos");

        roleService.addClientRoles(user.id(), "mto-stock-api", new RoleNamesRequest(List.of("stock-read")));
        assertEquals(List.of(username), usernamesOf(roleService.listClientRoleMembers("mto-stock-api", "stock-read", 0, 20)));
        assertTrue(roleService.listClientRoleMembers("mto-stock-api", "stock-read", 1, 20).isEmpty(),
                "'first' pagina de verdad contra Keycloak");

        assertThrows(ProtectedClientException.class, () -> roleService.listClientRoleMembers("realm-management", "realm-admin", 0, 20));
        assertThrows(RoleNotFoundException.class, () -> roleService.listClientRoleMembers("mto-stock-api", "stock-fly", 0, 20));

        userService.delete(user.id());
    }

    /**
     * Las sesiones son estado vivo del servidor: las abre un login de verdad y la API las cierra de
     * una en una o todas de golpe. El endpoint que borra una sesión es del realm y no del usuario,
     * así que un id ajeno cerraría la sesión de otra persona: el servicio comprueba antes de quién
     * es y aquí se ve que no la cierra.
     */
    @Test
    void theSessionsOfAUserAreOpenedByALoginAndClosedThroughTheApi() throws Exception {
        String username = "ses." + UUID.randomUUID().toString().substring(0, 8);
        UserResponse user = userService.create(new CreateUserRequest(username, "Sara", "Sesion",
                username + "@mto.local", true, true, null, null, null));
        userService.resetPassword(user.id(), new ResetPasswordRequest("Secreta.123", false));
        assertTrue(userService.listSessions(user.id()).isEmpty(), "Sin login no hay nada que cerrar");

        tokenFor(username, "Secreta.123");
        tokenFor(username, "Secreta.123");

        List<UserSessionResponse> sessions = userService.listSessions(user.id());
        assertEquals(2, sessions.size(), "Cada password grant abre su propia sesión");
        UserSessionResponse first = sessions.getFirst();
        assertEquals(username, first.username());
        assertEquals(List.of("mto-test-frontend"), first.clients(), "Sale el clientId, no el UUID interno");
        assertNotNull(first.startedAt());
        assertNotNull(first.lastAccessAt());

        userService.revokeSession(user.id(), first.id());
        assertEquals(1, userService.listSessions(user.id()).size());
        assertThrows(SessionNotFoundException.class, () -> userService.revokeSession(user.id(), first.id()),
                "La misma sesión ya no está");

        String otherUserId = userService.search(new UserSearchCriteria(null, "existente", null, null, null, List.of(), 0, 1))
                .content().getFirst().id();
        String remaining = userService.listSessions(user.id()).getFirst().id();
        assertThrows(SessionNotFoundException.class, () -> userService.revokeSession(otherUserId, remaining));
        assertEquals(1, userService.listSessions(user.id()).size(), "Y la sesión sigue abierta");

        userService.revokeAllSessions(user.id());
        assertTrue(userService.listSessions(user.id()).isEmpty());
        userService.revokeAllSessions(user.id());

        userService.delete(user.id());
    }

    /**
     * El agujero que esto cierra, de punta a punta: un token con {@code offline_access} no abre
     * sesion normal, sobrevive a cerrar todas las sesiones y sigue refrescandose. Deshabilitar al
     * usuario solo lo bloquea mientras esta deshabilitado —no revoca nada, al rehabilitarlo vuelve
     * a servir—, asi que lo unico que lo mata es cerrar su sesion offline.
     */
    @Test
    void anOfflineTokenSurvivesTheLogoutAndDiesWhenItsOwnSessionIsClosed() throws Exception {
        String username = "off." + UUID.randomUUID().toString().substring(0, 8);
        UserResponse user = userService.create(new CreateUserRequest(username, "Olga", "Offline",
                username + "@mto.local", true, true, null, null, null));
        userService.resetPassword(user.id(), new ResetPasswordRequest("Secreta.123", false));

        String refreshToken = offlineTokenFor(username, "Secreta.123");
        assertTrue(userService.listSessions(user.id()).isEmpty(), "Un token offline no deja sesion normal");

        List<UserSessionResponse> offline = userService.listOfflineSessions(user.id());
        assertEquals(1, offline.size());
        assertEquals(username, offline.getFirst().username());
        assertEquals(List.of("mto-test-frontend"), offline.getFirst().clients());
        assertNotNull(offline.getFirst().startedAt());

        // Cerrar todas las sesiones no lo toca: sigue refrescandose, que es justo el problema.
        userService.revokeAllSessions(user.id());
        assertEquals(1, userService.listOfflineSessions(user.id()).size());
        assertEquals(200, refreshStatus(refreshToken));

        // Deshabilitar bloquea el refresco mientras dura, pero no revoca nada: al rehabilitar
        // vuelve a servir. Deshabilitar no es revocar.
        userService.setEnabled(user.id(), false);
        assertEquals(400, refreshStatus(refreshToken));
        assertEquals(1, userService.listOfflineSessions(user.id()).size(), "La sesion offline sigue viva");
        userService.setEnabled(user.id(), true);
        assertEquals(200, refreshStatus(refreshToken));

        String sessionId = offline.getFirst().id();
        assertThrows(SessionNotFoundException.class, () -> userService.revokeOfflineSession(user.id(), "no-es-suya"));
        assertEquals(1, userService.listOfflineSessions(user.id()).size());

        userService.revokeOfflineSession(user.id(), sessionId);
        assertTrue(userService.listOfflineSessions(user.id()).isEmpty());
        assertEquals(400, refreshStatus(refreshToken), "Y ahora el token offline ya no vale");
        assertThrows(SessionNotFoundException.class, () -> userService.revokeOfflineSession(user.id(), sessionId));

        // Cerrar todas es idempotente, tambien sin ninguna abierta.
        offlineTokenFor(username, "Secreta.123");
        assertEquals(1, userService.listOfflineSessions(user.id()).size());
        userService.revokeAllOfflineSessions(user.id());
        assertTrue(userService.listOfflineSessions(user.id()).isEmpty());
        userService.revokeAllOfflineSessions(user.id());

        userService.delete(user.id());
    }

    /**
     * Las credenciales, que es lo que hace falta para quitarle a alguien un segundo factor perdido.
     * Aqui la credencial es una contrasena porque un OTP no se puede enrolar por la Admin API, pero
     * el mecanismo —y el 404 de un id ajeno— es el mismo.
     */
    @Test
    void theCredentialsOfAUserAreListedWithoutSecretsAndRemovedById() throws Exception {
        String username = "cred." + UUID.randomUUID().toString().substring(0, 8);
        UserResponse user = userService.create(new CreateUserRequest(username, "Carla", "Credencial",
                username + "@mto.local", true, true, null, null, null));
        assertTrue(userService.listCredentials(user.id()).isEmpty(), "Recien creado no tiene ninguna");

        userService.resetPassword(user.id(), new ResetPasswordRequest("Secreta.123", false));
        List<UserCredentialResponse> credentials = userService.listCredentials(user.id());
        assertEquals(1, credentials.size());
        assertEquals("password", credentials.getFirst().type());
        assertNotNull(credentials.getFirst().id());
        assertNotNull(credentials.getFirst().createdAt());
        assertEquals(200, tokenStatus(username, "Secreta.123"));

        // Una credencial de otra persona no se quita a traves de esta.
        String otherUserId = userService.search(new UserSearchCriteria(null, "existente", null, null, null, List.of(), 0, 1))
                .content().getFirst().id();
        String otherCredentialId = userService.listCredentials(otherUserId).getFirst().id();
        assertThrows(CredentialNotFoundException.class, () -> userService.deleteCredential(user.id(), otherCredentialId));
        assertEquals(1, userService.listCredentials(otherUserId).size(), "Y la suya sigue donde estaba");

        userService.deleteCredential(user.id(), credentials.getFirst().id());
        assertTrue(userService.listCredentials(user.id()).isEmpty());
        assertEquals(401, tokenStatus(username, "Secreta.123"), "Sin contrasena no se puede entrar");
        assertThrows(CredentialNotFoundException.class,
                () -> userService.deleteCredential(user.id(), credentials.getFirst().id()));

        userService.delete(user.id());
        assertThrows(UserNotFoundException.class, () -> userService.listCredentials(user.id()));
    }

    private static UserSearchCriteria byAttributes(String... attributes) {
        return new UserSearchCriteria(null, null, null, null, null, List.of(attributes), 0, 10);
    }

    private static List<String> usernamesOf(List<UserResponse> users) {
        return users.stream().map(UserResponse::username).toList();
    }

    private static String serverUrl() {
        return "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(HTTP_PORT);
    }

    private static String tokenFor(String username, String password) throws Exception {
        HttpResponse<String> response = token("grant_type=password&client_id=mto-test-frontend"
                + "&username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body()).get("access_token").asString();
    }

    /** Devuelve el refresh token offline. Necesita el scope, que en Keycloak es opcional por cliente. */
    private static String offlineTokenFor(String username, String password) throws Exception {
        HttpResponse<String> response = token("grant_type=password&client_id=mto-test-frontend&scope=offline_access"
                + "&username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body()).get("refresh_token").asString();
    }

    private static int refreshStatus(String refreshToken) throws Exception {
        return token("grant_type=refresh_token&client_id=mto-test-frontend&refresh_token="
                + URLEncoder.encode(refreshToken, StandardCharsets.UTF_8)).statusCode();
    }

    private static int tokenStatus(String username, String password) throws Exception {
        return token("grant_type=password&client_id=mto-test-frontend"
                + "&username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8)).statusCode();
    }

    private static HttpResponse<String> token(String form) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder()
                        .uri(URI.create(serverUrl() + "/realms/" + REALM + "/protocol/openid-connect/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode decode(String jwt) {
        String payload = jwt.split("\\.")[1];
        return JSON.readTree(Base64.getUrlDecoder().decode(payload));
    }

    private static String rolesOf(JsonNode token, String clientId) {
        return token.path("resource_access").path(clientId).path("roles").toString();
    }
}
