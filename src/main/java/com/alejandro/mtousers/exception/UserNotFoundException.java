package com.alejandro.mtousers.exception;

/** El usuario no existe en el realm (404). */
public class UserNotFoundException extends UsersException {

    public static final String CODE = "USR-404";

    public UserNotFoundException(String userId) {
        super(CODE, "User " + userId + " was not found");
    }
}
