package com.alejandro.mtousers.configuration.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Único punto de lectura del usuario autenticado. El resto de la aplicación no toca
 * {@code SecurityContextHolder} directamente. Es quien pone el actor en la auditoria.
 */
@Service
public class CurrentUserService {

    private static final String ANONYMOUS_PRINCIPAL = "anonymousUser";

    public Optional<Authentication> getAuthentication() {
        return Optional.ofNullable(SecurityContextHolder.getContext().getAuthentication())
                .filter(Authentication::isAuthenticated)
                .filter(authentication -> !ANONYMOUS_PRINCIPAL.equals(authentication.getPrincipal()));
    }

    public Optional<Jwt> getJwt() {
        return getAuthentication()
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(JwtAuthenticationToken.class::cast)
                .map(JwtAuthenticationToken::getToken);
    }

    /**
     * Devuelve vacío cuando no hay usuario autenticado, en lugar de sustituirlo por un nombre
     * inventado: quien llama decide qué registrar.
     */
    public Optional<String> getUsername() {
        return getJwt()
                .map(jwt -> jwt.getClaimAsString(JwtClaimNames.PREFERRED_USERNAME))
                .filter(value -> !value.isBlank());
    }

    public Optional<String> getUserId() {
        return getJwt().map(Jwt::getSubject);
    }

    public Optional<String> getEmail() {
        return getJwt().map(jwt -> jwt.getClaimAsString(JwtClaimNames.EMAIL));
    }

    public List<String> getAuthorities() {
        return getAuthentication()
                .map(Authentication::getAuthorities)
                .stream()
                .flatMap(Collection::stream)
                .map(GrantedAuthority::getAuthority)
                .toList();
    }

    public boolean hasAuthority(String authority) {
        return getAuthorities().contains(authority);
    }

    public boolean hasRole(String role) {
        String normalizedRole = role.startsWith(SecurityAuthorityPrefixes.ROLE_PREFIX)
                ? role
                : SecurityAuthorityPrefixes.ROLE_PREFIX + role;

        return hasAuthority(normalizedRole);
    }
}
