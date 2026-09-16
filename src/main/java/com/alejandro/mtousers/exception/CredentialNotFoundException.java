package com.alejandro.mtousers.exception;

/** La credencial no existe, o no es de ese usuario (404). */
public class CredentialNotFoundException extends UsersException {

    public static final String CODE = "CRED-404";

    public CredentialNotFoundException(String credentialId) {
        super(CODE, "Credential " + credentialId + " was not found");
    }
}
