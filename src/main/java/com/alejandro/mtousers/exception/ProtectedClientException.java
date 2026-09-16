package com.alejandro.mtousers.exception;

/**
 * Los roles de ese cliente no se administran desde esta API (400). Es la salvaguarda frente a
 * {@code manage-users}, que en Keycloak permite mapear cualquier rol, {@code realm-management}
 * incluido: sin esto, quien tuviera {@code users-roles-write} podría hacerse administrador del realm.
 */
public class ProtectedClientException extends UsersException {

    public static final String CODE = "ROL-PROTECTED-CLIENT";

    public ProtectedClientException(String clientId) {
        super(CODE, "Roles of client " + clientId + " are not managed through this API");
    }
}
