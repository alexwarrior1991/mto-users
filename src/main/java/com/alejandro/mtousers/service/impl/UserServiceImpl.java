package com.alejandro.mtousers.service.impl;

import com.alejandro.mtousers.dto.CreateUserRequest;
import com.alejandro.mtousers.dto.ExecuteActionsEmailRequest;
import com.alejandro.mtousers.dto.PageResponse;
import com.alejandro.mtousers.dto.RequiredAction;
import com.alejandro.mtousers.dto.ResetPasswordRequest;
import com.alejandro.mtousers.dto.UpdateUserRequest;
import com.alejandro.mtousers.dto.UserResponse;
import com.alejandro.mtousers.dto.UserSearchCriteria;
import com.alejandro.mtousers.dto.UserSessionResponse;
import com.alejandro.mtousers.exception.InvalidSearchException;
import com.alejandro.mtousers.exception.SessionNotFoundException;
import com.alejandro.mtousers.keycloak.KeycloakAdminGateway;
import com.alejandro.mtousers.mapper.UserMapper;
import com.alejandro.mtousers.service.AdminAuditLog;
import com.alejandro.mtousers.service.AdminAuditLog.AdminAction;
import com.alejandro.mtousers.service.UserService;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.representations.idm.UserSessionRepresentation;
import org.springframework.stereotype.Service;

import java.util.List;

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
        audit.record(AdminAction.USER_CREATED, userId, "username=" + request.username()
                + " temporaryPassword=" + (request.temporaryPassword() != null)
                + " requiredActions=" + (request.requiredActions() == null ? List.of() : request.requiredActions()));
        return get(userId);
    }

    @Override
    public UserResponse update(String userId, UpdateUserRequest request) {
        // Leer, aplicar y escribir: la Admin API reemplaza la representación entera, así que
        // mandar solo los campos cambiados dejaría a null todo lo demás.
        UserRepresentation representation = keycloak.findUser(userId);
        userMapper.applyUpdate(request, representation);
        keycloak.updateUser(userId, representation);
        audit.record(AdminAction.USER_UPDATED, userId, "fields=" + changedFields(request));
        return get(userId);
    }

    @Override
    public UserResponse setEnabled(String userId, boolean enabled) {
        UserRepresentation representation = keycloak.findUser(userId);
        representation.setEnabled(enabled);
        keycloak.updateUser(userId, representation);
        audit.record(enabled ? AdminAction.USER_ENABLED : AdminAction.USER_DISABLED, userId, "username=" + representation.getUsername());
        return get(userId);
    }

    @Override
    public void delete(String userId) {
        keycloak.deleteUser(userId);
        audit.record(AdminAction.USER_DELETED, userId, null);
    }

    @Override
    public void resetPassword(String userId, ResetPasswordRequest request) {
        keycloak.resetPassword(userId, request.password(), request.isTemporary());
        audit.record(AdminAction.PASSWORD_RESET, userId, "temporary=" + request.isTemporary());
    }

    @Override
    public void executeActionsEmail(String userId, ExecuteActionsEmailRequest request) {
        List<String> actions = request.actions().stream().map(RequiredAction::name).toList();
        keycloak.executeActionsEmail(userId, actions, request.lifespanSeconds(), request.clientId(), request.redirectUri());
        audit.record(AdminAction.ACTIONS_EMAIL_SENT, userId, "actions=" + actions);
    }

    @Override
    public List<UserSessionResponse> listSessions(String userId) {
        return userMapper.toSessionResponses(keycloak.listUserSessions(userId));
    }

    @Override
    public void revokeAllSessions(String userId) {
        keycloak.logoutUser(userId);
        audit.record(AdminAction.ALL_SESSIONS_REVOKED, userId, null);
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
        keycloak.deleteSession(sessionId);
        audit.record(AdminAction.SESSION_REVOKED, userId, "session=" + sessionId);
    }

    private static String changedFields(UpdateUserRequest request) {
        StringBuilder fields = new StringBuilder();
        if (request.firstName() != null) {
            fields.append("firstName ");
        }
        if (request.lastName() != null) {
            fields.append("lastName ");
        }
        if (request.email() != null) {
            fields.append("email ");
        }
        if (request.emailVerified() != null) {
            fields.append("emailVerified ");
        }
        if (request.attributes() != null) {
            fields.append("attributes ");
        }
        return fields.toString().trim();
    }
}
