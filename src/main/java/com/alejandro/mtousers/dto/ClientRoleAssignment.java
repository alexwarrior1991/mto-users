package com.alejandro.mtousers.dto;

import java.util.List;

/** Roles de un cliente, agrupados por su {@code clientId}. */
public record ClientRoleAssignment(String clientId, List<String> roles) {

    public ClientRoleAssignment {
        roles = roles == null ? List.of() : List.copyOf(roles);
    }
}
