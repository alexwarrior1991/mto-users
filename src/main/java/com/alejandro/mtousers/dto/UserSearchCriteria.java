package com.alejandro.mtousers.dto;

/**
 * Filtros de la búsqueda de usuarios, los mismos que admite la Admin API de Keycloak.
 * {@code search} busca en username, email, nombre y apellidos; {@code username} y {@code email}
 * filtran por ese campo; {@code enabled} y {@code emailVerified} se combinan con cualquiera de los
 * anteriores. {@code first} y {@code max} paginan por desplazamiento.
 */
public record UserSearchCriteria(
        String search,
        String username,
        String email,
        Boolean enabled,
        Boolean emailVerified,
        int first,
        int max
) {
}
