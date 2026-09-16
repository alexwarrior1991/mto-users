package com.alejandro.mtousers.exception;

/** Keycloak rechazó el alta porque ya hay un usuario con ese username o email (409). */
public class UserAlreadyExistsException extends UsersException {

    public static final String CODE = "USR-409";

    public UserAlreadyExistsException(String detail) {
        super(CODE, detail == null || detail.isBlank() ? "A user with the same username or email already exists" : detail);
    }
}
