package com.alejandro.mtousers.mapper;

import com.alejandro.mtousers.dto.CreateUserRequest;
import com.alejandro.mtousers.dto.UpdateUserRequest;
import com.alejandro.mtousers.dto.UserCredentialResponse;
import com.alejandro.mtousers.dto.UserResponse;
import com.alejandro.mtousers.dto.UserSessionResponse;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.representations.idm.UserSessionRepresentation;
import org.mapstruct.AfterMapping;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Entre la representación de usuario de Keycloak y los DTOs de la API. Es el único sitio, junto
 * con {@code keycloak/}, que ve {@link UserRepresentation}.
 */
@Mapper(config = MapStructCentralConfig.class)
public interface UserMapper {

    @Mapping(target = "createdAt", source = "createdTimestamp")
    UserResponse toResponse(UserRepresentation representation);

    List<UserResponse> toResponses(List<UserRepresentation> representations);

    /**
     * Solo los campos que un alta puede fijar: el resto de la representación (credenciales, grupos,
     * roles, federación...) se deja a Keycloak.
     */
    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "username", source = "username")
    @Mapping(target = "firstName", source = "firstName")
    @Mapping(target = "lastName", source = "lastName")
    @Mapping(target = "email", source = "email")
    @Mapping(target = "emailVerified", source = "emailVerified")
    @Mapping(target = "enabled", source = "enabled")
    @Mapping(target = "attributes", source = "attributes")
    @Mapping(target = "requiredActions", source = "requiredActions")
    UserRepresentation toRepresentation(CreateUserRequest request);

    /** Aplica los datos básicos sobre la representación existente; lo que venga a null no se toca. */
    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "firstName", source = "firstName")
    @Mapping(target = "lastName", source = "lastName")
    @Mapping(target = "email", source = "email")
    @Mapping(target = "emailVerified", source = "emailVerified")
    @Mapping(target = "attributes", source = "attributes")
    void applyUpdate(UpdateUserRequest request, @MappingTarget UserRepresentation representation);

    /**
     * Una sesión. Keycloak indexa los clientes por su UUID interno y pone el {@code clientId} como
     * valor; la API devuelve solo los nombres, ordenados, porque el UUID no significa nada fuera
     * del servidor.
     */
    default UserSessionResponse toSessionResponse(UserSessionRepresentation session) {
        if (session == null) {
            return null;
        }
        List<String> clients = session.getClients() == null ? List.of()
                : session.getClients().values().stream().filter(Objects::nonNull).sorted().toList();
        return new UserSessionResponse(
                session.getId(),
                session.getUsername(),
                session.getIpAddress(),
                Instant.ofEpochMilli(session.getStart()),
                Instant.ofEpochMilli(session.getLastAccess()),
                clients);
    }

    default List<UserSessionResponse> toSessionResponses(List<UserSessionRepresentation> sessions) {
        return sessions == null ? List.of() : sessions.stream().map(this::toSessionResponse).toList();
    }

    /**
     * Una credencial, sin nada del secreto ni de cómo está guardado: {@code secretData} Keycloak no
     * lo devuelve, y {@code credentialData} —el algoritmo de hash y sus parámetros— se queda aquí.
     */
    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "id", source = "id")
    @Mapping(target = "type", source = "type")
    @Mapping(target = "userLabel", source = "userLabel")
    @Mapping(target = "createdAt", source = "createdDate")
    UserCredentialResponse toCredentialResponse(CredentialRepresentation credential);

    List<UserCredentialResponse> toCredentialResponses(List<CredentialRepresentation> credentials);

    default Instant toInstant(Long epochMillis) {
        return epochMillis == null ? null : Instant.ofEpochMilli(epochMillis);
    }

    /** La contraseña temporal viaja como credencial del alta, no como campo del usuario. */
    @AfterMapping
    default void addTemporaryPassword(CreateUserRequest request, @MappingTarget UserRepresentation representation) {
        if (request.temporaryPassword() == null) {
            return;
        }
        CredentialRepresentation credential = new CredentialRepresentation();
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(request.temporaryPassword());
        credential.setTemporary(true);
        representation.setCredentials(List.of(credential));
    }
}
