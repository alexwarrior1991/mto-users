package com.alejandro.mtousers.service.impl;

import com.alejandro.mtousers.dto.CreateUserRequest;
import com.alejandro.mtousers.dto.ExecuteActionsEmailRequest;
import com.alejandro.mtousers.dto.PageResponse;
import com.alejandro.mtousers.dto.RequiredAction;
import com.alejandro.mtousers.dto.ResetPasswordRequest;
import com.alejandro.mtousers.dto.UpdateUserRequest;
import com.alejandro.mtousers.dto.UserResponse;
import com.alejandro.mtousers.dto.UserSearchCriteria;
import com.alejandro.mtousers.keycloak.KeycloakAdminGateway;
import com.alejandro.mtousers.mapper.UserMapper;
import com.alejandro.mtousers.service.AdminAuditLog;
import com.alejandro.mtousers.service.AdminAuditLog.AdminAction;
import com.alejandro.mtousers.service.UserService;
import org.keycloak.representations.idm.UserRepresentation;
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
