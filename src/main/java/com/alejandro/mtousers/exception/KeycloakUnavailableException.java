package com.alejandro.mtousers.exception;

/** Keycloak no respondió: conexión rechazada o timeout (503, con {@code Retry-After}). */
public class KeycloakUnavailableException extends UsersException {

    public static final String CODE = "KC-503";

    public KeycloakUnavailableException(String message, Throwable cause) {
        super(CODE, message, cause);
    }
}
