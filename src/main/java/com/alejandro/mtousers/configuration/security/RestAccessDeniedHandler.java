package com.alejandro.mtousers.configuration.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;

/**
 * Respuesta a una petición autenticada pero sin permisos, con el mismo reparto que
 * {@link RestAuthenticationEntryPoint}: la cabecera {@code WWW-Authenticate} la pone el handler de
 * Spring Security —que añade {@code error="insufficient_scope"} cuando la autenticación es un token
 * OAuth2— y el cuerpo sale del contrato de errores de la API ({@code ProblemDetail}).
 */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final AccessDeniedHandler bearerTokenAccessDeniedHandler = new BearerTokenAccessDeniedHandler();

    private final HandlerExceptionResolver handlerExceptionResolver;

    public RestAccessDeniedHandler(
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver handlerExceptionResolver
    ) {
        this.handlerExceptionResolver = handlerExceptionResolver;
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException
    ) throws IOException, ServletException {
        bearerTokenAccessDeniedHandler.handle(request, response, accessDeniedException);

        handlerExceptionResolver.resolveException(request, response, null, accessDeniedException);
    }
}
