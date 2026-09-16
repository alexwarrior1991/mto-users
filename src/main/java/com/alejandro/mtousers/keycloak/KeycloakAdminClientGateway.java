package com.alejandro.mtousers.keycloak;

import com.alejandro.mtousers.configuration.keycloak.KeycloakAdminProperties;
import com.alejandro.mtousers.dto.UserSearchCriteria;
import com.alejandro.mtousers.exception.ClientNotFoundException;
import com.alejandro.mtousers.exception.KeycloakAccessException;
import com.alejandro.mtousers.exception.KeycloakRequestException;
import com.alejandro.mtousers.exception.KeycloakUnavailableException;
import com.alejandro.mtousers.exception.KeycloakUpstreamException;
import com.alejandro.mtousers.exception.ProfileNotFoundException;
import com.alejandro.mtousers.exception.UserAlreadyExistsException;
import com.alejandro.mtousers.exception.UserNotFoundException;
import com.alejandro.mtousers.exception.UsersException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.MappingsRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * {@link KeycloakAdminGateway} sobre el admin client oficial.
 *
 * <p>Toda llamada pasa por {@link #call(String, Supplier)}, que traduce las excepciones JAX-RS del
 * cliente ({@code NotFoundException}, {@code ClientErrorException} 409, {@code BadRequestException},
 * {@code NotAuthorizedException}, {@code ProcessingException}...) a las de negocio. Los 404 que
 * tienen un significado concreto —el usuario, el cliente o el rol pedidos no existen— los
 * traducen los propios métodos, porque solo ellos saben qué se estaba buscando.</p>
 *
 * <p>{@code UsersResource.create()} devuelve un {@code Response} en vez de lanzar: se lee el
 * estado, se saca el id de {@code Location} y se cierra siempre, porque un {@code Response} sin
 * cerrar retiene una conexión del pool.</p>
 */
public class KeycloakAdminClientGateway implements KeycloakAdminGateway {

    private static final Logger LOGGER = LoggerFactory.getLogger(KeycloakAdminClientGateway.class);

    /** Errores del endpoint de token (RFC 6749): un 400 con uno de estos es la cuenta de servicio, no la petición. */
    private static final Set<String> OAUTH_CLIENT_ERRORS = Set.of(
            "invalid_client", "unauthorized_client", "invalid_grant", "invalid_request", "invalid_scope",
            "unsupported_grant_type", "access_denied");

    private final RealmResource realm;
    private final UsersQueryResource usersQuery;
    private final KeycloakAdminProperties properties;

    public KeycloakAdminClientGateway(Keycloak keycloak, KeycloakAdminProperties properties) {
        this.realm = keycloak.realm(properties.realm());
        this.usersQuery = keycloak.proxy(UsersQueryResource.class, URI.create(properties.adminRealmUrl() + "/users"));
        this.properties = properties;
    }

    @Override
    public List<UserRepresentation> searchUsers(UserSearchCriteria criteria) {
        return call("search users", () -> usersQuery.search(
                blankToNull(criteria.search()), blankToNull(criteria.username()), blankToNull(criteria.email()),
                criteria.enabled(), criteria.emailVerified(), criteria.first(), criteria.max(), false));
    }

    @Override
    public int countUsers(UserSearchCriteria criteria) {
        Integer count = call("count users", () -> usersQuery.count(
                blankToNull(criteria.search()), blankToNull(criteria.username()), blankToNull(criteria.email()),
                criteria.enabled(), criteria.emailVerified()));
        return count == null ? 0 : count;
    }

    @Override
    public UserRepresentation findUser(String userId) {
        return call("find user " + userId, () -> {
            try {
                return realm.users().get(userId).toRepresentation();
            } catch (NotFoundException notFound) {
                throw new UserNotFoundException(userId);
            }
        });
    }

    @Override
    public String createUser(UserRepresentation user) {
        return call("create user " + user.getUsername(), () -> {
            Response response = realm.users().create(user);
            try {
                if (response.getStatus() == Response.Status.CREATED.getStatusCode()) {
                    return CreatedResponseUtil.getCreatedId(response);
                }
                throw translate("create user " + user.getUsername(), response.getStatus(), readError(response), null);
            } finally {
                response.close();
            }
        });
    }

    @Override
    public void updateUser(String userId, UserRepresentation user) {
        run("update user " + userId, () -> {
            try {
                realm.users().get(userId).update(user);
            } catch (NotFoundException notFound) {
                throw new UserNotFoundException(userId);
            }
        });
    }

    @Override
    public void deleteUser(String userId) {
        run("delete user " + userId, () -> {
            try {
                realm.users().get(userId).remove();
            } catch (NotFoundException notFound) {
                throw new UserNotFoundException(userId);
            }
        });
    }

    @Override
    public void resetPassword(String userId, String password, boolean temporary) {
        // La contraseña no entra en el nombre de la operación: ese texto acaba en el log.
        run("reset password of user " + userId, () -> {
            CredentialRepresentation credential = new CredentialRepresentation();
            credential.setType(CredentialRepresentation.PASSWORD);
            credential.setValue(password);
            credential.setTemporary(temporary);
            try {
                realm.users().get(userId).resetPassword(credential);
            } catch (NotFoundException notFound) {
                throw new UserNotFoundException(userId);
            }
        });
    }

    @Override
    public void executeActionsEmail(String userId, List<String> actions, Integer lifespanSeconds, String clientId, String redirectUri) {
        run("send actions email to user " + userId, () -> {
            try {
                realm.users().get(userId).executeActionsEmail(blankToNull(clientId), blankToNull(redirectUri), lifespanSeconds, actions);
            } catch (NotFoundException notFound) {
                throw new UserNotFoundException(userId);
            }
        });
    }

    @Override
    public List<ClientRepresentation> listClients() {
        return call("list clients", () -> realm.clients().findAll());
    }

    @Override
    public ClientRepresentation findClient(String clientId) {
        return call("find client " + clientId, () -> {
            List<ClientRepresentation> clients = realm.clients().findByClientId(clientId);
            return clients.stream()
                    .filter(client -> clientId.equals(client.getClientId()))
                    .findFirst()
                    .orElseThrow(() -> new ClientNotFoundException(clientId));
        });
    }

    @Override
    public List<RoleRepresentation> listClientRoles(String clientUuid) {
        return call("list roles of client " + clientUuid, () -> realm.clients().get(clientUuid).roles().list());
    }

    @Override
    public MappingsRepresentation getUserRoleMappings(String userId) {
        return call("read role mappings of user " + userId, () -> {
            try {
                return realm.users().get(userId).roles().getAll();
            } catch (NotFoundException notFound) {
                throw new UserNotFoundException(userId);
            }
        });
    }

    @Override
    public void addClientRoles(String userId, String clientUuid, List<RoleRepresentation> roles) {
        run("add client roles to user " + userId, () -> {
            try {
                realm.users().get(userId).roles().clientLevel(clientUuid).add(roles);
            } catch (NotFoundException notFound) {
                // El cliente y los roles se acaban de resolver: lo que falta es el usuario.
                throw new UserNotFoundException(userId);
            }
        });
    }

    @Override
    public void removeClientRoles(String userId, String clientUuid, List<RoleRepresentation> roles) {
        run("remove client roles from user " + userId, () -> {
            try {
                realm.users().get(userId).roles().clientLevel(clientUuid).remove(roles);
            } catch (NotFoundException notFound) {
                throw new UserNotFoundException(userId);
            }
        });
    }

    @Override
    public List<RoleRepresentation> listRealmRoles() {
        return call("list realm roles", () -> realm.roles().list());
    }

    @Override
    public RoleRepresentation findRealmRole(String roleName) {
        return call("find realm role " + roleName, () -> {
            try {
                return realm.roles().get(roleName).toRepresentation();
            } catch (NotFoundException notFound) {
                throw new ProfileNotFoundException(roleName);
            }
        });
    }

    @Override
    public Set<RoleRepresentation> getRealmRoleComposites(String roleName) {
        return call("read composites of realm role " + roleName, () -> {
            try {
                return realm.roles().get(roleName).getRoleComposites();
            } catch (NotFoundException notFound) {
                throw new ProfileNotFoundException(roleName);
            }
        });
    }

    @Override
    public List<RoleRepresentation> getUserRealmRoles(String userId) {
        return call("read realm roles of user " + userId, () -> {
            try {
                return realm.users().get(userId).roles().realmLevel().listAll();
            } catch (NotFoundException notFound) {
                throw new UserNotFoundException(userId);
            }
        });
    }

    @Override
    public void addRealmRoles(String userId, List<RoleRepresentation> roles) {
        run("add realm roles to user " + userId, () -> {
            try {
                realm.users().get(userId).roles().realmLevel().add(roles);
            } catch (NotFoundException notFound) {
                throw new UserNotFoundException(userId);
            }
        });
    }

    @Override
    public void removeRealmRoles(String userId, List<RoleRepresentation> roles) {
        run("remove realm roles from user " + userId, () -> {
            try {
                realm.users().get(userId).roles().realmLevel().remove(roles);
            } catch (NotFoundException notFound) {
                throw new UserNotFoundException(userId);
            }
        });
    }

    private void run(String operation, Runnable action) {
        call(operation, () -> {
            action.run();
            return null;
        });
    }

    /**
     * Ejecuta una llamada y traduce lo que el admin client lance. Las excepciones de negocio ya
     * traducidas por quien llama pasan tal cual.
     */
    private <T> T call(String operation, Supplier<T> action) {
        try {
            return action.get();
        } catch (UsersException translated) {
            throw translated;
        } catch (NotAuthorizedException | ForbiddenException rejected) {
            throw translate(operation, status(rejected), readError(rejected.getResponse()), rejected);
        } catch (WebApplicationException failure) {
            throw translate(operation, status(failure), readError(failure.getResponse()), failure);
        } catch (ProcessingException unreachable) {
            LOGGER.warn("Keycloak did not answer while trying to {}: {}", operation, rootMessage(unreachable));
            throw new KeycloakUnavailableException(
                    "Keycloak did not answer while trying to " + operation + ": " + rootMessage(unreachable), unreachable);
        }
    }

    private UsersException translate(String operation, int status, KeycloakError error, Throwable cause) {
        String detail = error == null ? null : error.detail();
        if (status == 401 || status == 403 || (status == 400 && error != null && error.isOAuthClientError())) {
            return new KeycloakAccessException("Keycloak rejected the service account of this service while trying to "
                    + operation + " (HTTP " + status + "): check app.keycloak.admin-client-secret and the realm-management"
                    + " roles of client " + properties.adminClientId() + (detail == null ? "" : ": " + detail), cause);
        }
        if (status == 400) {
            return new KeycloakRequestException(detail);
        }
        if (status == 409) {
            return new UserAlreadyExistsException(detail);
        }
        LOGGER.warn("Keycloak answered HTTP {} while trying to {}: {}", status, operation, detail);
        return new KeycloakUpstreamException("Keycloak answered HTTP " + status + " while trying to " + operation
                + (detail == null ? "" : ": " + detail), cause);
    }

    private static int status(WebApplicationException exception) {
        return exception.getResponse() == null ? 0 : exception.getResponse().getStatus();
    }

    /**
     * Lee el cuerpo de error de Keycloak, que tiene dos formas: {@code errorMessage} en la Admin
     * API y {@code error}/{@code error_description} en el endpoint de token y en algunas
     * validaciones (la política de contraseñas, por ejemplo). Nunca lanza: un cuerpo ilegible
     * solo deja el detalle vacío.
     */
    static KeycloakError readError(Response response) {
        if (response == null) {
            return null;
        }
        try {
            if (!response.hasEntity()) {
                return null;
            }
            response.bufferEntity();
            Map<?, ?> body = response.readEntity(Map.class);
            if (body == null) {
                return null;
            }
            return new KeycloakError(text(body.get("errorMessage")), text(body.get("error")), text(body.get("error_description")));
        } catch (RuntimeException unreadable) {
            return null;
        } finally {
            response.close();
        }
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    /** Lo que Keycloak dijo en el cuerpo de un error. */
    record KeycloakError(String errorMessage, String error, String errorDescription) {

        boolean isOAuthClientError() {
            return errorMessage == null && error != null && OAUTH_CLIENT_ERRORS.contains(error);
        }

        String detail() {
            if (errorMessage != null && !errorMessage.isBlank()) {
                return errorMessage;
            }
            if (errorDescription != null && !errorDescription.isBlank()) {
                return errorDescription;
            }
            return error;
        }
    }
}
