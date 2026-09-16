package com.alejandro.mtousers.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Un usuario del realm, sin credenciales ni nada interno de Keycloak. */
public record UserResponse(
        String id,
        String username,
        String firstName,
        String lastName,
        String email,
        Boolean emailVerified,
        Boolean enabled,
        Instant createdAt,
        Map<String, List<String>> attributes,
        List<String> requiredActions
) {
}
