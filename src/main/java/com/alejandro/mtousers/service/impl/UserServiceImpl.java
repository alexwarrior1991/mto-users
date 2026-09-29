package com.alejandro.mtousers.service.impl;

import com.alejandro.mtousers.dto.CreateUserRequest;
import com.alejandro.mtousers.dto.ExecuteActionsEmailRequest;
import com.alejandro.mtousers.dto.PageResponse;
import com.alejandro.mtousers.dto.RequiredAction;
import com.alejandro.mtousers.dto.ResetPasswordRequest;
import com.alejandro.mtousers.dto.UpdateUserRequest;
import com.alejandro.mtousers.dto.UserCredentialResponse;
import com.alejandro.mtousers.dto.UserResponse;
import com.alejandro.mtousers.dto.UserSearchCriteria;
import com.alejandro.mtousers.dto.UserSessionResponse;
import com.alejandro.mtousers.exception.CredentialNotFoundException;
import com.alejandro.mtousers.exception.InvalidSearchException;
import com.alejandro.mtousers.exception.SessionNotFoundException;
import com.alejandro.mtousers.keycloak.KeycloakAdminGateway;
import com.alejandro.mtousers.mapper.UserMapper;
import com.alejandro.mtousers.service.AdminAuditLog;
import com.alejandro.mtousers.service.AdminAuditLog.AdminAction;
import com.alejandro.mtousers.service.UserService;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.representations.idm.UserSessionRepresentation;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
class UserServiceImpl implements UserService {

    private final KeycloakAdminGateway keycloak;
    private final UserMapper userMapper;
    private final AdminAuditLog audit;

    UserServiceImpl(KeycloakAdminGateway keycloak, UserMapper userMapper, AdminAuditLog audit) {
        this.keycloak = keycloak;
        this.userMapper = userMapper;
        this.audit = audit;
    }

    @Override
    public PageResponse<UserResponse> search(UserSearchCriteria criteria) {
        // Keycloak aplica 'search' y descarta 'q' cuando llegan juntos, sin decir nada: una
        // busqueda por atributo devolveria usuarios que no tienen ese atributo. Se rechaza antes
        // de preguntar, que es lo unico que no engaña a quien llama.
        if (criteria.hasSearch() && !criteria.attributes().isEmpty()) {
            throw new InvalidSearchException("'search' and 'attribute' cannot be combined: Keycloak ignores the "
                    + "attribute filter when a free-text search is present. Use 'username' or 'email' instead of 'search'.");
        }
        // Y la misma historia con una clave repetida: Keycloak parsea 'q' a un mapa, de modo que
        // 'dept:taller dept:obra' filtra solo por el ultimo par y el otro desaparece en silencio.
        List<String> repeated = criteria.repeatedAttributeKeys();
        if (!repeated.isEmpty()) {
            throw new InvalidSearchException("'attribute' cannot repeat a key " + repeated
                    + ": Keycloak keeps only the last value of each key, so the other filters would be ignored.");
        }
        List<UserResponse> users = userMapper.toResponses(keycloak.searchUsers(criteria));
        int total = keycloak.countUsers(criteria);
        return new PageResponse<>(users, criteria.first(), criteria.max(), total);
    }

    @Override
    public UserResponse get(String userId) {
        return userMapper.toResponse(keycloak.findUser(userId));
    }

    @Override
    public UserResponse create(CreateUserRequest request) {
        UserRepresentation representation = userMapper.toRepresentation(request);
        if (representation.isEnabled() == null) {
            // Keycloak crea deshabilitado por defecto, que casi nunca es lo que se quiere al dar de alta.
            representation.setEnabled(true);
        }
        String userId = keycloak.createUser(representation);
        audit.record(AdminAction.USER_CREATED, userId, request.username(), detail(
                "enabled", representation.isEnabled(),
                "temporaryCredential", request.temporaryPassword() != null,
                "requiredActions", request.requiredActions() == null ? List.of() : request.requiredActions()));
        return get(userId);
    }

    @Override
    public UserResponse update(String userId, UpdateUserRequest request) {
        // Leer, aplicar y escribir: la Admin API reemplaza la representación entera, así que
        // mandar solo los campos cambiados dejaría a null todo lo demás.
        UserRepresentation representation = keycloak.findUser(userId);
        userMapper.applyUpdate(request, representation);
        keycloak.updateUser(userId, representation);
        audit.record(AdminAction.USER_UPDATED, userId, representation.getUsername(),
                detail("fields", changedFields(request)));
        return get(userId);
    }

    @Override
    public UserResponse setEnabled(String userId, boolean enabled) {
        UserRepresentation representation = keycloak.findUser(userId);
        representation.setEnabled(enabled);
        keycloak.updateUser(userId, representation);
        audit.record(enabled ? AdminAction.USER_ENABLED : AdminAction.USER_DISABLED, userId,
                representation.getUsername(), Map.of());
        return get(userId);
    }

    /**
     * Se lee el usuario antes de borrarlo para que el rastro lleve su nombre: después del borrado
     * nadie puede resolver el id, y un aviso «se ha borrado el usuario 2f1c9d1e-…» no le dice nada
     * a quien lo lee.
     */
    @Override
    public void delete(String userId) {
        UserRepresentation representation = keycloak.findUser(userId);
        keycloak.deleteUser(userId);
        audit.record(AdminAction.USER_DELETED, userId, representation.getUsername(), Map.of());
    }

    @Override
    public void resetPassword(String userId, ResetPasswordRequest request) {
        keycloak.resetPassword(userId, request.password(), request.isTemporary());
        audit.record(AdminAction.PASSWORD_RESET, userId, detail("temporary", request.isTemporary()));
    }

    @Override
    public void executeActionsEmail(String userId, ExecuteActionsEmailRequest request) {
        List<String> actions = request.actions().stream().map(RequiredAction::name).toList();
        keycloak.executeActionsEmail(userId, actions, request.lifespanSeconds(), request.clientId(), request.redirectUri());
        audit.record(AdminAction.ACTIONS_EMAIL_SENT, userId, detail("actions", actions, "clientId", request.clientId()));
    }

    @Override
    public List<UserSessionResponse> listSessions(String userId) {
        return userMapper.toSessionResponses(keycloak.listUserSessions(userId));
    }

    @Override
    public void revokeAllSessions(String userId) {
        keycloak.logoutUser(userId);
        audit.record(AdminAction.ALL_SESSIONS_REVOKED, userId, Map.of());
    }

    /**
     * El endpoint de Keycloak que cierra una sesion es del realm, no del usuario, asi que un id de
     * sesion ajeno cerraria la sesion de otra persona. Se comprueba antes que la sesion esta entre
     * las de este usuario; si no lo esta, para esta API no existe.
     */
    @Override
    public void revokeSession(String userId, String sessionId) {
        boolean belongsToUser = keycloak.listUserSessions(userId).stream()
                .map(UserSessionRepresentation::getId)
                .anyMatch(sessionId::equals);
        if (!belongsToUser) {
            throw new SessionNotFoundException(sessionId);
        }
        keycloak.deleteSession(sessionId, false);
        audit.record(AdminAction.SESSION_REVOKED, userId, detail("session", sessionId));
    }

    /**
     * Las sesiones offline no aparecen en {@link #listSessions(String)} ni las cierra
     * {@link #revokeAllSessions(String)}: son las de los tokens con {@code offline_access}, que
     * estan hechos para sobrevivir al cierre de sesion. Un usuario deshabilitado con un token
     * offline vivo sigue pudiendo refrescarlo, asi que cerrarlas es su propia operacion.
     *
     * <p>Keycloak las consulta cliente a cliente, y el indice de que clientes preguntar son los
     * consentimientos del usuario.</p>
     */
    @Override
    public List<UserSessionResponse> listOfflineSessions(String userId) {
        return userMapper.toSessionResponses(offlineSessions(userId));
    }

    @Override
    public void revokeAllOfflineSessions(String userId) {
        List<String> sessionIds = offlineSessions(userId).stream().map(UserSessionRepresentation::getId).toList();
        sessionIds.forEach(sessionId -> keycloak.deleteSession(sessionId, true));
        audit.record(AdminAction.ALL_OFFLINE_SESSIONS_REVOKED, userId, detail("sessions", sessionIds.size()));
    }

    /** Misma comprobacion que en una sesion normal, y por el mismo motivo. */
    @Override
    public void revokeOfflineSession(String userId, String sessionId) {
        boolean belongsToUser = offlineSessions(userId).stream()
                .map(UserSessionRepresentation::getId)
                .anyMatch(sessionId::equals);
        if (!belongsToUser) {
            throw new SessionNotFoundException(sessionId);
        }
        keycloak.deleteSession(sessionId, true);
        audit.record(AdminAction.OFFLINE_SESSION_REVOKED, userId, detail("session", sessionId));
    }

    @Override
    public List<UserCredentialResponse> listCredentials(String userId) {
        return userMapper.toCredentialResponses(keycloak.listCredentials(userId));
    }

    /**
     * Se busca primero entre las del usuario para poder decir en la auditoria <em>que</em> se ha
     * quitado —quitar un OTP no es lo mismo que quitar una contrasena vieja— y para que un id que
     * no exista sea un 404 de credencial y no de usuario.
     */
    @Override
    public void deleteCredential(String userId, String credentialId) {
        String type = keycloak.listCredentials(userId).stream()
                .filter(credential -> credentialId.equals(credential.getId()))
                .map(CredentialRepresentation::getType)
                .findFirst()
                .orElseThrow(() -> new CredentialNotFoundException(credentialId));

        keycloak.deleteCredential(userId, credentialId);
        audit.record(AdminAction.CREDENTIAL_DELETED, userId, detail("credential", credentialId, "type", type));
    }

    private List<UserSessionRepresentation> offlineSessions(String userId) {
        return keycloak.findClientsWithOfflineTokens(userId).stream()
                .flatMap(clientUuid -> keycloak.listOfflineSessions(userId, clientUuid).stream())
                .toList();
    }

    /** Lo que venia en la peticion, no lo que de verdad cambio: es lo que el rastro enumera. */
    private static List<String> changedFields(UpdateUserRequest request) {
        List<String> fields = new ArrayList<>();
        if (request.firstName() != null) {
            fields.add("firstName");
        }
        if (request.lastName() != null) {
            fields.add("lastName");
        }
        if (request.email() != null) {
            fields.add("email");
        }
        if (request.emailVerified() != null) {
            fields.add("emailVerified");
        }
        if (request.attributes() != null) {
            fields.add("attributes");
        }
        return fields;
    }

    /** Un mapa con orden, porque el rastro se lee: {@code Map.of} lo barajaria y no admite nulos. */
    static Map<String, Object> detail(Object... keysAndValues) {
        Map<String, Object> detail = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            detail.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return detail;
    }
}
