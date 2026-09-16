package com.alejandro.mtousers.exception;

import com.alejandro.mtousers.configuration.web.CorrelationIdFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    /**
     * El handler de {@link UsersException} es una red de seguridad que responde 422. Si una
     * excepción nueva se queda solo con él, la API contesta 422 donde quería contestar 404 o 400 y
     * el test de la clase no lo ve, porque llamar al método a mano acierta igual: lo que decide es
     * la lista de {@code @ExceptionHandler}. Por eso cada subclase tiene que estar nombrada.
     */
    @Test
    void everyBusinessExceptionIsRoutedByItsOwnHandlerAndNotByTheCatchAll() {
        Set<String> routed = Arrays.stream(GlobalExceptionHandler.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(ExceptionHandler.class))
                .filter(Objects::nonNull)
                .flatMap(annotation -> Arrays.stream(annotation.value()))
                .filter(type -> type != UsersException.class)
                .map(Class::getName)
                .collect(Collectors.toSet());

        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(UsersException.class));
        List<String> unrouted = scanner.findCandidateComponents(UsersException.class.getPackageName()).stream()
                .map(BeanDefinition::getBeanClassName)
                .filter(name -> !routed.contains(name))
                .sorted()
                .toList();

        assertEquals(List.of(), unrouted, "Les falta su propio @ExceptionHandler y caerían en el 422 genérico");
    }

    @Test
    void aMissingUserIs404ProblemJsonWithCodeTypeAndCorrelation() {
        MDC.put(CorrelationIdFilter.MDC_KEY, "corr-1");
        MockHttpServletRequest request = request("GET", "/api/v1/users/abc");

        ResponseEntity<ProblemDetail> response = handler.handleUserNotFound(new UserNotFoundException("abc"), request);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals(MediaType.APPLICATION_PROBLEM_JSON, response.getHeaders().getContentType());
        ProblemDetail body = response.getBody();
        assertNotNull(body);
        assertEquals("User abc was not found", body.getDetail());
        assertEquals("/api/v1/users/abc", body.getInstance().toString());
        assertEquals("urn:problem:mto-users:USR-404", body.getType().toString());
        assertEquals("USR-404", body.getProperties().get("errorCode"));
        assertEquals("corr-1", body.getProperties().get("correlationId"));
        assertNotNull(body.getProperties().get("timestamp"));
        assertNull(body.getProperties().get("validationErrors"), "Solo los 400 de validación llevan la lista");
    }

    @Test
    void everyBusinessExceptionHasItsStatusAndCode() {
        MockHttpServletRequest request = request("POST", "/api/v1/users");

        assertEquals(HttpStatus.CONFLICT, handler.handleConflict(new UserAlreadyExistsException(null), request).getStatusCode());
        assertEquals("USR-409", codeOf(handler.handleConflict(new UserAlreadyExistsException(null), request)));
        assertEquals("A user with the same username or email already exists", handler.handleConflict(new UserAlreadyExistsException(" "), request).getBody().getDetail());
        assertEquals(HttpStatus.NOT_FOUND, handler.handleNotFound(new ClientNotFoundException("x"), request).getStatusCode());
        assertEquals("ROL-404", codeOf(handler.handleNotFound(new RoleNotFoundException("mto-stock-api", List.of("a")), request)));
        assertEquals("PRF-404", codeOf(handler.handleNotFound(new ProfileNotFoundException("mto-x"), request)));
        assertEquals(HttpStatus.BAD_REQUEST, handler.handleBadRequest(new ProtectedClientException("realm-management"), request).getStatusCode());
        assertEquals("ROL-PROTECTED-CLIENT", codeOf(handler.handleBadRequest(new ProtectedClientException("realm-management"), request)));
        assertEquals("KC-400", codeOf(handler.handleBadRequest(new KeycloakRequestException("bad"), request)));
        assertEquals(HttpStatus.BAD_REQUEST, handler.handleBadRequest(new InvalidSearchException("no"), request).getStatusCode());
        assertEquals("SEARCH-400", codeOf(handler.handleBadRequest(new InvalidSearchException("no"), request)));
        assertEquals(HttpStatus.NOT_FOUND, handler.handleNotFound(new SessionNotFoundException("s"), request).getStatusCode());
        assertEquals("SES-404", codeOf(handler.handleNotFound(new SessionNotFoundException("s"), request)));
        assertEquals(HttpStatus.BAD_GATEWAY, handler.handleKeycloakAccess(new KeycloakAccessException("nope", null), request).getStatusCode());
        assertEquals("KC-ACCESS", codeOf(handler.handleKeycloakAccess(new KeycloakAccessException("nope", null), request)));
        assertEquals(HttpStatus.BAD_GATEWAY, handler.handleKeycloakUpstream(new KeycloakUpstreamException("boom", null), request).getStatusCode());
        assertEquals("KC-502", codeOf(handler.handleKeycloakUpstream(new KeycloakUpstreamException("boom", null), request)));
    }

    @Test
    void anUnreachableKeycloakIs503WithRetryAfter() {
        ResponseEntity<ProblemDetail> response = handler.handleKeycloakUnavailable(
                new KeycloakUnavailableException("Keycloak did not answer", null), request("GET", "/api/v1/users"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("10", response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
        assertEquals(MediaType.APPLICATION_PROBLEM_JSON, response.getHeaders().getContentType());
        assertEquals("KC-503", codeOf(response));
    }

    @Test
    void securityFailuresKeepTheirCodes() {
        MockHttpServletRequest request = request("GET", "/api/v1/users");

        assertEquals(HttpStatus.UNAUTHORIZED, handler.handleAuthentication(new BadCredentialsException("no"), request).getStatusCode());
        assertEquals("AUTH-401", codeOf(handler.handleAuthentication(new BadCredentialsException("no"), request)));
        assertEquals(HttpStatus.FORBIDDEN, handler.handleAccessDenied(new AccessDeniedException("no"), request).getStatusCode());
        assertEquals("AUTH-403", codeOf(handler.handleAccessDenied(new AccessDeniedException("no"), request)));
    }

    @Test
    void unexpectedExceptionsDoNotLeakDetails() {
        ResponseEntity<ProblemDetail> response = handler.handleException(new IllegalStateException("secret"), request("GET", "/api/v1/users"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("APP-500", codeOf(response));
        assertFalse(response.getBody().getDetail().contains("secret"));
    }

    @Test
    void correlationIsOmittedWhenThereIsNoneInTheMdc() {
        Map<String, Object> properties = handler.handleUserNotFound(new UserNotFoundException("abc"), request("GET", "/x")).getBody().getProperties();

        assertTrue(properties.containsKey("errorCode"));
        assertFalse(properties.containsKey("correlationId"));
    }

    private static String codeOf(ResponseEntity<ProblemDetail> response) {
        return (String) response.getBody().getProperties().get("errorCode");
    }

    private static MockHttpServletRequest request(String method, String uri) {
        return new MockHttpServletRequest(method, uri);
    }
}
