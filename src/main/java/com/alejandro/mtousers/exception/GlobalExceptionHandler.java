package com.alejandro.mtousers.exception;

import com.alejandro.mtousers.configuration.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.ObjectError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Convierte cada excepción en un {@code ProblemDetail} (RFC 9457) servido como
 * {@code application/problem+json}, el mismo formato que devuelve el gateway. Además de los campos
 * estándar lleva {@code errorCode} (estable, para que un cliente distinga la causa sin leer el
 * mensaje), {@code correlationId} (para encontrar la petición en el log), {@code timestamp} y, en
 * los 400 de validación, {@code validationErrors}.
 *
 * <p>Los 401 y 403 de la cadena de filtros también llegan aquí, a través de
 * {@code RestAuthenticationEntryPoint} y {@code RestAccessDeniedHandler}.</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    static final String PROBLEM_TYPE_PREFIX = "urn:problem:mto-users:";
    static final String ERROR_CODE = "errorCode";
    static final String CORRELATION_ID = "correlationId";
    static final String TIMESTAMP = "timestamp";
    static final String VALIDATION_ERRORS = "validationErrors";

    /** Lo que se le dice a un cliente que Keycloak no responde: cuándo volver a intentarlo. */
    static final String RETRY_AFTER_SECONDS = "10";

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleUserNotFound(UserNotFoundException exception, HttpServletRequest request) {
        return business(exception, HttpStatus.NOT_FOUND, request);
    }

    @ExceptionHandler({ClientNotFoundException.class, RoleNotFoundException.class, ProfileNotFoundException.class,
            SessionNotFoundException.class})
    public ResponseEntity<ProblemDetail> handleNotFound(UsersException exception, HttpServletRequest request) {
        return business(exception, HttpStatus.NOT_FOUND, request);
    }

    @ExceptionHandler(UserAlreadyExistsException.class)
    public ResponseEntity<ProblemDetail> handleConflict(UserAlreadyExistsException exception, HttpServletRequest request) {
        return business(exception, HttpStatus.CONFLICT, request);
    }

    @ExceptionHandler({ProtectedClientException.class, KeycloakRequestException.class, InvalidSearchException.class})
    public ResponseEntity<ProblemDetail> handleBadRequest(UsersException exception, HttpServletRequest request) {
        return business(exception, HttpStatus.BAD_REQUEST, request);
    }

    /** La cuenta de servicio no vale o le faltan permisos: se registra como error, no como aviso. */
    @ExceptionHandler(KeycloakAccessException.class)
    public ResponseEntity<ProblemDetail> handleKeycloakAccess(KeycloakAccessException exception, HttpServletRequest request) {
        LOGGER.error("Keycloak rejected the service account for {} {}: {}", request.getMethod(), request.getRequestURI(),
                exception.getMessage(), exception);
        return problem(HttpStatus.BAD_GATEWAY, "Bad Gateway", exception.getMessage(), exception.getErrorCode(), request, List.of());
    }

    @ExceptionHandler(KeycloakUpstreamException.class)
    public ResponseEntity<ProblemDetail> handleKeycloakUpstream(KeycloakUpstreamException exception, HttpServletRequest request) {
        LOGGER.error("Keycloak failed for {} {}: {}", request.getMethod(), request.getRequestURI(), exception.getMessage(), exception);
        return problem(HttpStatus.BAD_GATEWAY, "Bad Gateway", exception.getMessage(), exception.getErrorCode(), request, List.of());
    }

    @ExceptionHandler(KeycloakUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleKeycloakUnavailable(KeycloakUnavailableException exception, HttpServletRequest request) {
        LOGGER.error("Keycloak is unavailable for {} {}: {}", request.getMethod(), request.getRequestURI(), exception.getMessage());
        ResponseEntity<ProblemDetail> response = problem(HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable",
                exception.getMessage(), exception.getErrorCode(), request, List.of());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .headers(response.getHeaders())
                .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                .body(response.getBody());
    }

    /** Cualquier otra excepción de negocio que no tenga todavía un estado más concreto. */
    @ExceptionHandler(UsersException.class)
    public ResponseEntity<ProblemDetail> handleBusiness(UsersException exception, HttpServletRequest request) {
        return business(exception, HttpStatus.UNPROCESSABLE_CONTENT, request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleMethodArgumentNotValid(MethodArgumentNotValidException exception, HttpServletRequest request) {
        List<ValidationError> errors = new ArrayList<>();
        exception.getBindingResult().getFieldErrors()
                .forEach(error -> errors.add(new ValidationError(error.getField(), message(error))));
        exception.getBindingResult().getGlobalErrors()
                .forEach(error -> errors.add(new ValidationError(error.getObjectName(), message(error))));
        return problem(HttpStatus.BAD_REQUEST, "Bad Request", "Request validation failed.", "REQ-VALIDATION", request, errors);
    }

    /** Validación de parámetros y variables de ruta declarada con constraints en el controlador. */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ProblemDetail> handleHandlerMethodValidation(HandlerMethodValidationException exception, HttpServletRequest request) {
        List<ValidationError> errors = exception.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new ValidationError(result.getMethodParameter().getParameterName(),
                                error.getDefaultMessage() == null ? "Validation error" : error.getDefaultMessage())))
                .toList();
        return problem(HttpStatus.BAD_REQUEST, "Bad Request", "Request validation failed.", "REQ-VALIDATION", request, errors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException exception, HttpServletRequest request) {
        List<ValidationError> errors = exception.getConstraintViolations().stream()
                .sorted(Comparator.comparing(violation -> violation.getPropertyPath().toString()))
                .map(GlobalExceptionHandler::toValidationError)
                .toList();
        return problem(HttpStatus.BAD_REQUEST, "Bad Request", "Request validation failed.", "REQ-VALIDATION", request, errors);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleHttpMessageNotReadable(HttpServletRequest request) {
        LOGGER.warn("Malformed request body for {} {}", request.getMethod(), request.getRequestURI());
        return problem(HttpStatus.BAD_REQUEST, "Bad Request", "Request body is missing or malformed.", "REQ-400", request, List.of());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ProblemDetail> handleMissingParameter(MissingServletRequestParameterException exception, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "Bad Request", "Missing request parameter.", "REQ-400", request,
                List.of(new ValidationError(exception.getParameterName(), "is required")));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException exception, HttpServletRequest request) {
        Class<?> requiredType = exception.getRequiredType();
        String typeName = requiredType == null ? "required type" : requiredType.getSimpleName();
        return problem(HttpStatus.BAD_REQUEST, "Bad Request", "Invalid request parameter.", "REQ-400", request,
                List.of(new ValidationError(exception.getName(), "must be a valid " + typeName)));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException exception, HttpServletRequest request) {
        return problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported Media Type", "Unsupported media type.", "REQ-415", request, List.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMethodNotSupported(HttpRequestMethodNotSupportedException exception, HttpServletRequest request) {
        return problem(HttpStatus.METHOD_NOT_ALLOWED, "Method Not Allowed", "Method not allowed.", "REQ-405", request, List.of());
    }

    /**
     * 401 para toda la jerarquía: una petición sin token llega aquí desde la cadena de filtros y,
     * sin este handler, caería en el genérico y saldría como 500.
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException exception, HttpServletRequest request) {
        LOGGER.warn("Unauthenticated request to {} {}: {}", request.getMethod(), request.getRequestURI(), exception.getMessage());
        return problem(HttpStatus.UNAUTHORIZED, "Unauthorized", "Authentication is required to access this resource.", "AUTH-401", request, List.of());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException exception, HttpServletRequest request) {
        LOGGER.warn("Access denied for {} {}: {}", request.getMethod(), request.getRequestURI(), exception.getMessage());
        return problem(HttpStatus.FORBIDDEN, "Forbidden", "The authenticated user is not allowed to perform this operation.", "AUTH-403", request, List.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ProblemDetail> handleNoResourceFound(HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "Not Found", "Resource was not found.", "HTTP-404", request, List.of());
    }

    /** 500 sin detalles: lo concreto queda en el log, localizable por el identificador de correlación. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleException(Exception exception, HttpServletRequest request) {
        LOGGER.error("Unexpected exception while processing {} {}", request.getMethod(), request.getRequestURI(), exception);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
                "An unexpected error occurred. Please contact support.", "APP-500", request, List.of());
    }

    private ResponseEntity<ProblemDetail> business(UsersException exception, HttpStatus status, HttpServletRequest request) {
        LOGGER.warn("{} for {} {}: {}", exception.getErrorCode(), request.getMethod(), request.getRequestURI(), exception.getMessage());
        return problem(status, status.getReasonPhrase(), exception.getMessage(), exception.getErrorCode(), request, List.of());
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String title, String detail, String errorCode,
                                                  HttpServletRequest request, List<ValidationError> validationErrors) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
        problemDetail.setTitle(title);
        problemDetail.setType(URI.create(PROBLEM_TYPE_PREFIX + errorCode));
        problemDetail.setInstance(URI.create(request.getRequestURI()));
        problemDetail.setProperty(ERROR_CODE, errorCode);
        problemDetail.setProperty(TIMESTAMP, Instant.now().toString());
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            problemDetail.setProperty(CORRELATION_ID, correlationId);
        }
        if (!validationErrors.isEmpty()) {
            problemDetail.setProperty(VALIDATION_ERRORS, validationErrors);
        }

        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    private static ValidationError toValidationError(ConstraintViolation<?> violation) {
        return new ValidationError(violation.getPropertyPath().toString(), violation.getMessage());
    }

    private static String message(ObjectError error) {
        return error.getDefaultMessage() == null ? "Validation error" : error.getDefaultMessage();
    }
}
