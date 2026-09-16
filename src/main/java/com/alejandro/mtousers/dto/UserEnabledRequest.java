package com.alejandro.mtousers.dto;

import jakarta.validation.constraints.NotNull;

/** Habilitar o deshabilitar un usuario. Deshabilitado no puede iniciar sesión, pero conserva todo. */
public record UserEnabledRequest(@NotNull Boolean enabled) {
}
