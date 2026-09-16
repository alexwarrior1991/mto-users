package com.alejandro.mtousers.exception;

/** Keycloak respondió, pero con un error que no tiene traducción de negocio (502). */
public class KeycloakUpstreamException extends UsersException {

    public static final String CODE = "KC-502";

    public KeycloakUpstreamException(String message, Throwable cause) {
        super(CODE, message, cause);
    }
}
