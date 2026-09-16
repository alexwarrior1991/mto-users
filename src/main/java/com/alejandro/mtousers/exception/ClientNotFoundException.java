package com.alejandro.mtousers.exception;

/** No hay ningún cliente con ese {@code clientId} en el realm (404). */
public class ClientNotFoundException extends UsersException {

    public static final String CODE = "CLI-404";

    public ClientNotFoundException(String clientId) {
        super(CODE, "Client " + clientId + " was not found");
    }
}
