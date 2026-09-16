package com.alejandro.mtousers.exception;

/** Keycloak rechazó la petición como inválida (400); el detalle es el {@code errorMessage} de Keycloak. */
public class KeycloakRequestException extends UsersException {

    public static final String CODE = "KC-400";

    public KeycloakRequestException(String detail) {
        super(CODE, detail == null || detail.isBlank() ? "Keycloak rejected the request as invalid" : detail);
    }
}
