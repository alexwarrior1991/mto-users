package com.alejandro.mtousers.controller;

import com.alejandro.mtousers.configuration.security.SecurityConfiguration;
import com.alejandro.mtousers.dto.CreateUserRequest;
import com.alejandro.mtousers.dto.ExecuteActionsEmailRequest;
import com.alejandro.mtousers.dto.PageResponse;
import com.alejandro.mtousers.dto.ResetPasswordRequest;
import com.alejandro.mtousers.dto.UpdateUserRequest;
import com.alejandro.mtousers.dto.UserEnabledRequest;
import com.alejandro.mtousers.dto.UserResponse;
import com.alejandro.mtousers.dto.UserSearchCriteria;
import com.alejandro.mtousers.dto.UserSessionResponse;
import com.alejandro.mtousers.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

/**
 * Usuarios del realm. Sin lógica: valida, delega en {@link UserService} y responde.
 *
 * <p>Los ids de Keycloak son UUID, pero se admite el charset algo más amplio de los usuarios
 * federados sin importar ({@code f:...}); lo que se rechaza es cualquier cosa que no pueda ser un
 * id, antes de llamar a Keycloak con ella.</p>
 */
@RestController
@RequestMapping(SecurityConfiguration.API)
@Tag(name = "Users")
public class UserController {

    static final String USER_ID_PATTERN = "[A-Za-z0-9:._-]{1,255}";
    static final String USER_ID_MESSAGE = "must be a Keycloak user id";
    static final String ATTRIBUTE_PATTERN = "[^:\\s]{1,255}:[^\\s]{0,255}";
    static final String ATTRIBUTE_MESSAGE = "must be a key:value pair without spaces";
    static final String SESSION_ID_PATTERN = "[A-Za-z0-9:._-]{1,255}";
    static final String SESSION_ID_MESSAGE = "must be a Keycloak session id";

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    @Operation(summary = "Search users",
            description = "Offset pagination (first/max). 'search' looks in username, email, first and last name; "
                    + "'username' and 'email' filter by that field; 'enabled' and 'emailVerified' combine with any of "
                    + "them. 'attribute' filters by user attribute, repeatable, as key:value; it cannot be combined "
                    + "with 'search' (Keycloak would silently ignore it) and needs the realm to allow unmanaged "
                    + "attributes. Attribute values match exactly.")
    public PageResponse<UserResponse> search(
            @RequestParam(required = false) @Size(max = 255) String search,
            @RequestParam(required = false) @Size(max = 255) String username,
            @RequestParam(required = false) @Size(max = 255) String email,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) Boolean emailVerified,
            @RequestParam(required = false) List<@Pattern(regexp = ATTRIBUTE_PATTERN, message = ATTRIBUTE_MESSAGE) String> attribute,
            @RequestParam(defaultValue = "0") @Min(0) int first,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int max
    ) {
        return userService.search(new UserSearchCriteria(search, username, email, enabled, emailVerified, attribute, first, max));
    }

    @PostMapping
    @Operation(summary = "Create a user", description = "Optionally with a temporary password and required actions. Enabled unless told otherwise.")
    @ApiResponse(responseCode = "201", description = "Created; Location points to the new user")
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        UserResponse created = userService.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @GetMapping("/{userId}")
    @Operation(summary = "Get a user")
    public UserResponse get(@PathVariable @Pattern(regexp = USER_ID_PATTERN, message = USER_ID_MESSAGE) String userId) {
        return userService.get(userId);
    }

    @PutMapping("/{userId}")
    @Operation(summary = "Update the basic data of a user", description = "Fields left out or null are kept as they are. The username cannot change.")
    public UserResponse update(@PathVariable @Pattern(regexp = USER_ID_PATTERN, message = USER_ID_MESSAGE) String userId,
                               @Valid @RequestBody UpdateUserRequest request) {
        return userService.update(userId, request);
    }

    @PatchMapping("/{userId}/enabled")
    @Operation(summary = "Enable or disable a user")
    public UserResponse setEnabled(@PathVariable @Pattern(regexp = USER_ID_PATTERN, message = USER_ID_MESSAGE) String userId,
                                   @Valid @RequestBody UserEnabledRequest request) {
        return userService.setEnabled(userId, request.enabled());
    }

    @DeleteMapping("/{userId}")
    @Operation(summary = "Delete a user")
    @ApiResponse(responseCode = "204", description = "Deleted")
    public ResponseEntity<Void> delete(@PathVariable @Pattern(regexp = USER_ID_PATTERN, message = USER_ID_MESSAGE) String userId) {
        userService.delete(userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{userId}/reset-password")
    @Operation(summary = "Set a (temporary) password", description = "With temporary=true, the default, Keycloak forces a change at the next login.")
    @ApiResponse(responseCode = "204", description = "Password set")
    public ResponseEntity<Void> resetPassword(@PathVariable @Pattern(regexp = USER_ID_PATTERN, message = USER_ID_MESSAGE) String userId,
                                              @Valid @RequestBody ResetPasswordRequest request) {
        userService.resetPassword(userId, request);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{userId}/sessions")
    @Operation(summary = "Open sessions of a user", description = "Every session Keycloak currently holds for the user, with the clients it has gone through.")
    public List<UserSessionResponse> listSessions(@PathVariable @Pattern(regexp = USER_ID_PATTERN, message = USER_ID_MESSAGE) String userId) {
        return userService.listSessions(userId);
    }

    @DeleteMapping("/{userId}/sessions")
    @Operation(summary = "Close every session of a user", description = "Idempotent: a user with no open session is not an error. Disabling a user does not close the sessions already open, this does.")
    @ApiResponse(responseCode = "204", description = "Sessions closed")
    public ResponseEntity<Void> revokeAllSessions(@PathVariable @Pattern(regexp = USER_ID_PATTERN, message = USER_ID_MESSAGE) String userId) {
        userService.revokeAllSessions(userId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{userId}/sessions/{sessionId}")
    @Operation(summary = "Close one session of a user", description = "404 if that session is not one of this user's: the Keycloak endpoint behind it belongs to the realm, not to the user.")
    @ApiResponse(responseCode = "204", description = "Session closed")
    public ResponseEntity<Void> revokeSession(
            @PathVariable @Pattern(regexp = USER_ID_PATTERN, message = USER_ID_MESSAGE) String userId,
            @PathVariable @Pattern(regexp = SESSION_ID_PATTERN, message = SESSION_ID_MESSAGE) String sessionId) {
        userService.revokeSession(userId, sessionId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{userId}/execute-actions-email")
    @Operation(summary = "Email the user a link to complete required actions", description = "UPDATE_PASSWORD, VERIFY_EMAIL... Needs SMTP configured in the realm.")
    @ApiResponse(responseCode = "202", description = "Email requested")
    public ResponseEntity<Void> executeActionsEmail(@PathVariable @Pattern(regexp = USER_ID_PATTERN, message = USER_ID_MESSAGE) String userId,
                                                    @Valid @RequestBody ExecuteActionsEmailRequest request) {
        userService.executeActionsEmail(userId, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }
}
