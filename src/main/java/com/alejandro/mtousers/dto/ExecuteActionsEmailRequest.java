package com.alejandro.mtousers.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Correo de Keycloak con un enlace para completar acciones (cambiar la contraseña, verificar el
 * email...). Requiere SMTP configurado en el realm.
 *
 * @param actions         acciones que el enlace obliga a completar
 * @param lifespanSeconds validez del enlace; por defecto la del realm (12 horas)
 * @param clientId        cliente al que volver al terminar, con {@code redirectUri}
 * @param redirectUri     URI de retorno, que tiene que ser una de las registradas en ese cliente
 */
public record ExecuteActionsEmailRequest(
        @NotEmpty List<@NotNull RequiredAction> actions,
        @Positive Integer lifespanSeconds,
        @Size(max = 255) String clientId,
        @Size(max = 2048) String redirectUri
) {
}
