package com.alejandro.mtousers.service.impl;

import com.alejandro.mtousers.configuration.security.CurrentUserService;
import com.alejandro.mtousers.exception.SelfAdministrationException;
import com.alejandro.mtousers.exception.ServiceAccountProtectedException;
import com.alejandro.mtousers.keycloak.KeycloakAdminGateway;
import com.alejandro.mtousers.keycloak.ServiceAccounts;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.stereotype.Component;

/**
 * Sobre quién no se puede actuar, decidido en un solo sitio y antes de cualquier escritura en
 * Keycloak:
 *
 * <ul>
 *   <li><b>Las cuentas de servicio solo se leen.</b> Toda acción que cambie algo de una
 *       ({@link #requireAdministrable(String)}) se rechaza con {@code USR-SVC-403}.</li>
 *   <li><b>Nadie cambia sus propios permisos ni se saca a sí mismo.</b> Roles, perfiles,
 *       deshabilitar y borrar ({@link #requireNotSelf(String, String)}) se rechazan con
 *       {@code USR-SELF-403} cuando el objetivo es el {@code sub} de quien llama.</li>
 * </ul>
 *
 * <p>Toda mutación de los tres servicios pasa por {@link #requireAdministrable(String)} como
 * primera llamada a Keycloak; {@code BusinessLayerTest} recorre los métodos de las tres interfaces
 * y falla si uno nuevo se lo salta. Un rechazo no deja línea de auditoría ni evento: no ha pasado
 * nada que anunciar.</p>
 */
@Component
class TargetUserPolicy {

    private final KeycloakAdminGateway keycloak;
    private final CurrentUserService currentUser;

    TargetUserPolicy(KeycloakAdminGateway keycloak, CurrentUserService currentUser) {
        this.keycloak = keycloak;
        this.currentUser = currentUser;
    }

    /**
     * Lee el usuario objetivo y lo devuelve si se puede administrar. Un usuario que no existe es su
     * 404 de siempre, antes que cualquier otra cosa.
     */
    UserRepresentation requireAdministrable(String userId) {
        UserRepresentation target = keycloak.findUser(userId);
        if (ServiceAccounts.isServiceAccount(target)) {
            throw ServiceAccountProtectedException.readOnly(target.getUsername());
        }
        return target;
    }

    /** Sin llamar a Keycloak: basta el {@code sub} del token. */
    void requireNotSelf(String userId, String operation) {
        if (currentUser.getUserId().filter(userId::equals).isPresent()) {
            throw new SelfAdministrationException(operation);
        }
    }

    /** El prefijo de las cuentas de servicio no se usa para un usuario nuevo: es lo que las distingue. */
    void requireUsernameNotReserved(String username) {
        if (ServiceAccounts.isReservedUsername(username)) {
            throw ServiceAccountProtectedException.reservedUsername(ServiceAccounts.USERNAME_PREFIX);
        }
    }
}
