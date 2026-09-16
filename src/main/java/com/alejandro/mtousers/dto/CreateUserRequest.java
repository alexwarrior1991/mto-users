package com.alejandro.mtousers.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * Alta de usuario. {@code temporaryPassword}, si viene, se fija como contraseña temporal: Keycloak
 * obliga a cambiarla en el primer inicio de sesión. Nunca aparece en {@link #toString()} para que
 * no acabe en un log por accidente.
 */
public record CreateUserRequest(
        @NotBlank @Size(max = 255) @Pattern(regexp = "[a-zA-Z0-9._@-]+", message = "may only contain letters, digits, '.', '_', '@' and '-'")
        String username,
        @Size(max = 255) String firstName,
        @Size(max = 255) String lastName,
        @Email @Size(max = 255) String email,
        Boolean emailVerified,
        Boolean enabled,
        Map<@NotBlank @Size(max = 255) String, @NotNull List<@Size(max = 255) String>> attributes,
        List<@NotNull RequiredAction> requiredActions,
        @Size(min = 8, max = 255) String temporaryPassword
) {

    @Override
    public String toString() {
        return "CreateUserRequest[username=" + username + ", firstName=" + firstName + ", lastName=" + lastName
                + ", email=" + email + ", emailVerified=" + emailVerified + ", enabled=" + enabled
                + ", attributes=" + attributes + ", requiredActions=" + requiredActions
                + ", temporaryPassword=" + (temporaryPassword == null ? "null" : "******") + "]";
    }
}
