package com.alejandro.mtousers.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * Datos básicos de un usuario. Lo que venga a {@code null} no se toca: el username no se puede
 * cambiar y el estado habilitado tiene su propio endpoint.
 */
public record UpdateUserRequest(
        @Size(max = 255) String firstName,
        @Size(max = 255) String lastName,
        @Email @Size(max = 255) String email,
        Boolean emailVerified,
        Map<@NotBlank @Size(max = 255) String, @NotNull List<@Size(max = 255) String>> attributes
) {
}
