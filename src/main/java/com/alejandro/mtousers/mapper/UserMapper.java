package com.alejandro.mtousers.mapper;

import com.alejandro.mtousers.dto.CreateUserRequest;
import com.alejandro.mtousers.dto.UpdateUserRequest;
import com.alejandro.mtousers.dto.UserResponse;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.mapstruct.AfterMapping;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.time.Instant;
import java.util.List;

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
