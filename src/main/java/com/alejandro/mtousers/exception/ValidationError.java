package com.alejandro.mtousers.exception;

/** Un error de validación de un campo, tal como sale en {@code validationErrors} del ProblemDetail. */
public record ValidationError(String field, String message) {
}
