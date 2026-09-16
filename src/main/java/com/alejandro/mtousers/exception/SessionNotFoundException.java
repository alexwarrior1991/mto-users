package com.alejandro.mtousers.exception;

/** La sesión no existe, o ya se había cerrado (404). */
public class SessionNotFoundException extends UsersException {

    public static final String CODE = "SES-404";

    public SessionNotFoundException(String sessionId) {
        super(CODE, "Session " + sessionId + " was not found");
    }
}
