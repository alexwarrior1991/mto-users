package com.alejandro.mtousers.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Contraseña nueva. Con {@code temporary} a true (el valor por defecto) Keycloak obliga a cambiarla
 * en el primer inicio de sesión. La contraseña nunca aparece en {@link #toString()}.
 */
public record ResetPasswordRequest(
        @NotBlank @Size(min = 8, max = 255) String password,
        Boolean temporary
) {

    public boolean isTemporary() {
        return temporary == null || temporary;
    }

    @Override
    public String toString() {
        return "ResetPasswordRequest[password=******, temporary=" + temporary + "]";
    }
}
