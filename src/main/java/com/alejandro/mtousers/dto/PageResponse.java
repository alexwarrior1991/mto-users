package com.alejandro.mtousers.dto;

import java.util.List;

/**
 * Página al estilo de Keycloak: desplazamiento ({@code first}) y tamaño ({@code max}), no número de
 * página. {@code total} sale de {@code /users/count} con los mismos filtros.
 */
public record PageResponse<T>(List<T> content, int first, int max, long total) {

    public PageResponse {
        content = content == null ? List.of() : List.copyOf(content);
    }
}
