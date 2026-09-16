package com.alejandro.mtousers.exception;

import java.util.Collection;

/** Alguno de los roles pedidos no existe en el cliente (404). */
public class RoleNotFoundException extends UsersException {

    public static final String CODE = "ROL-404";

    public RoleNotFoundException(String clientId, Collection<String> missingRoles) {
        super(CODE, "Client " + clientId + " has no role named " + String.join(", ", missingRoles));
    }
}
