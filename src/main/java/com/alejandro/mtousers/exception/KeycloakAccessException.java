package com.alejandro.mtousers.exception;

/**
 * Keycloak rechazó la cuenta de servicio de este servicio (502): el secreto no es el que espera o
 * le faltan roles de {@code realm-management}. No es culpa del cliente de la API, que no puede
 * hacer nada, y por eso no es un 401 ni un 403: es la puerta de enlace la que no está bien
 * configurada.
 */
public class KeycloakAccessException extends UsersException {

    public static final String CODE = "KC-ACCESS";

    public KeycloakAccessException(String message, Throwable cause) {
        super(CODE, message, cause);
    }
}
