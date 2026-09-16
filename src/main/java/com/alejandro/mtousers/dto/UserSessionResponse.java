package com.alejandro.mtousers.dto;

import java.time.Instant;
import java.util.List;

/**
 * Una sesión abierta de un usuario. {@code clients} son los clientes por los que ha pasado esa
 * sesión, por su {@code clientId}; Keycloak los devuelve indexados por el UUID interno del cliente,
 * que no significa nada fuera del servidor.
 */
public record UserSessionResponse(
        String id,
        String username,
        String ipAddress,
        Instant startedAt,
        Instant lastAccessAt,
        List<String> clients
) {

    public UserSessionResponse {
        clients = clients == null ? List.of() : List.copyOf(clients);
    }
}
