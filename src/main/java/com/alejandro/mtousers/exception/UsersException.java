package com.alejandro.mtousers.exception;

/**
 * Base de las excepciones de negocio de la API. Cada subclase lleva un código estable
 * ({@code errorCode}) para que un cliente distinga la causa sin analizar el mensaje; el estado HTTP
 * lo decide {@link GlobalExceptionHandler}.
 */
public abstract class UsersException extends RuntimeException {

    private final String errorCode;

    protected UsersException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    protected UsersException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
