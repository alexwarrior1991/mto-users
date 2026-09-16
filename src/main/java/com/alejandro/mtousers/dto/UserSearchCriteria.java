package com.alejandro.mtousers.dto;

import java.util.Collections;
import java.util.List;

/**
 * Filtros de la búsqueda de usuarios, los mismos que admite la Admin API de Keycloak.
 * {@code search} busca en username, email, nombre y apellidos; {@code username} y {@code email}
 * filtran por ese campo; {@code enabled} y {@code emailVerified} se combinan con cualquiera de los
 * anteriores. {@code first} y {@code max} paginan por desplazamiento.
 *
 * <p>{@code attributes} son pares {@code clave:valor} de atributos del usuario, que Keycloak recibe
 * como su parámetro {@code q}. Dos claves distintas se combinan con Y —quien salga los tiene todos—,
 * pero hay dos trampas que esta API prefiere cortar con un 400 antes que pasarlas al servidor:
 * <b>no se combinan con {@code search}</b> (con los dos presentes Keycloak aplica {@code search} y
 * descarta {@code q} sin avisar) y <b>no se repite una clave</b> (Keycloak parsea {@code q} a un
 * mapa, así que de {@code dept:taller dept:obra} solo sobrevive el último par y el filtro que se
 * pierde no deja rastro).</p>
 */
public record UserSearchCriteria(
        String search,
        String username,
        String email,
        Boolean enabled,
        Boolean emailVerified,
        List<String> attributes,
        int first,
        int max
) {

    public UserSearchCriteria {
        attributes = attributes == null ? List.of() : List.copyOf(attributes);
    }

    /** Los atributos en la forma que espera Keycloak: {@code clave:valor} separados por espacios. */
    public String attributeQuery() {
        return attributes.isEmpty() ? null : String.join(" ", attributes);
    }

    public boolean hasSearch() {
        return search != null && !search.isBlank();
    }

    /** Claves de atributo que aparecen más de una vez, en orden de aparición y sin repetirse. */
    public List<String> repeatedAttributeKeys() {
        List<String> keys = attributes.stream().map(UserSearchCriteria::keyOf).toList();
        return keys.stream()
                .filter(key -> Collections.frequency(keys, key) > 1)
                .distinct()
                .toList();
    }

    private static String keyOf(String attribute) {
        int separator = attribute.indexOf(':');
        return separator < 0 ? attribute : attribute.substring(0, separator);
    }
}
