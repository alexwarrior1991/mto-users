package com.alejandro.mtousers.exception;

/**
 * El usuario objetivo es la cuenta de servicio de un cliente, y esta API solo la deja leer (403).
 * Deshabilitar, borrar o quitarle roles a {@code service-account-mto-users-svc} deja a este mismo
 * servicio sin acceso a Keycloak; hacérselo a la de un hermano lo rompe en silencio. Sus roles se
 * administran con el realm (los ficheros de {@code keycloak/} y {@code apply-partials.sh}), no aquí.
 *
 * <p>Es un 403 con código propio y no {@code AUTH-403}: a quien llama no le falta ningún permiso, y
 * pedir otro no lo arreglaría.</p>
 */
public class ServiceAccountProtectedException extends UsersException {

    public static final String CODE = "USR-SVC-403";

    private ServiceAccountProtectedException(String message) {
        super(CODE, message);
    }

    public static ServiceAccountProtectedException readOnly(String username) {
        return new ServiceAccountProtectedException("User " + username
                + " is the service account of a client: it is read-only through this API");
    }

    public static ServiceAccountProtectedException reservedUsername(String prefix) {
        return new ServiceAccountProtectedException("Usernames starting with " + prefix
                + " are reserved for the service accounts of the realm's clients");
    }
}
