package com.alejandro.mtousers.controller;

import com.alejandro.mtousers.configuration.security.SecurityConfiguration;
import com.alejandro.mtousers.dto.ClientResponse;
import com.alejandro.mtousers.dto.ClientRoleResponse;
import com.alejandro.mtousers.dto.RoleNamesRequest;
import com.alejandro.mtousers.dto.UserRolesResponse;
import com.alejandro.mtousers.service.RoleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Roles de cliente: el catálogo ({@code /roles/clients/...}) y los de cada usuario
 * ({@code /{userId}/roles/...}). Todo cuelga de {@code /api/v1/users} porque el gateway reescribe
 * {@code /api/users} a esa raíz; los segmentos literales ganan a {@code {userId}} en Spring MVC.
 */
@RestController
@RequestMapping(SecurityConfiguration.API)
@Tag(name = "Roles")
public class RoleController {

    static final String CLIENT_ID_PATTERN = "[A-Za-z0-9._-]{1,255}";
    static final String CLIENT_ID_MESSAGE = "must be a Keycloak client id";

    private final RoleService roleService;

    public RoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    @GetMapping("/roles/clients")
    @Operation(summary = "List the clients whose roles can be assigned", description = "Keycloak's own clients (realm-management, account...) are never listed.")
    public List<ClientResponse> listClients() {
        return roleService.listClients();
    }

    @GetMapping("/roles/clients/{clientId}")
    @Operation(summary = "List the roles of a client")
    public List<ClientRoleResponse> listClientRoles(
            @PathVariable @Pattern(regexp = CLIENT_ID_PATTERN, message = CLIENT_ID_MESSAGE) String clientId) {
        return roleService.listClientRoles(clientId);
    }

    @GetMapping("/{userId}/roles")
    @Operation(summary = "Roles directly assigned to a user", description = "Realm roles (profiles among them) and client roles. Roles granted through a profile are not expanded here.")
    public UserRolesResponse getUserRoles(
            @PathVariable @Pattern(regexp = UserController.USER_ID_PATTERN, message = UserController.USER_ID_MESSAGE) String userId) {
        return roleService.getUserRoles(userId);
    }

    @PutMapping("/{userId}/roles/clients/{clientId}")
    @Operation(summary = "Assign roles of a client to a user", description = "Adds to what the user already has. Returns the updated assignments.")
    public UserRolesResponse addClientRoles(
            @PathVariable @Pattern(regexp = UserController.USER_ID_PATTERN, message = UserController.USER_ID_MESSAGE) String userId,
            @PathVariable @Pattern(regexp = CLIENT_ID_PATTERN, message = CLIENT_ID_MESSAGE) String clientId,
            @Valid @RequestBody RoleNamesRequest request) {
        return roleService.addClientRoles(userId, clientId, request);
    }

    @DeleteMapping("/{userId}/roles/clients/{clientId}")
    @Operation(summary = "Remove roles of a client from a user", description = "The roles to remove travel in the body. Returns the updated assignments.")
    public UserRolesResponse removeClientRoles(
            @PathVariable @Pattern(regexp = UserController.USER_ID_PATTERN, message = UserController.USER_ID_MESSAGE) String userId,
            @PathVariable @Pattern(regexp = CLIENT_ID_PATTERN, message = CLIENT_ID_MESSAGE) String clientId,
            @Valid @RequestBody RoleNamesRequest request) {
        return roleService.removeClientRoles(userId, clientId, request);
    }
}
