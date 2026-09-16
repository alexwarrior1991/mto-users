package com.alejandro.mtousers.dto;

import java.time.Instant;

/**
 * Una credencial del usuario, para poder quitarla: el caso real es el segundo factor de alguien que
 * ha perdido el móvil y necesita volver a enrolarse.
 *
 * <p>Solo lo que hace falta para identificarla y decidir. La Admin API devuelve además
 * {@code credentialData}, que para una contraseña trae el algoritmo de hash y sus parámetros
 * ({@code argon2}, iteraciones, memoria); no es el secreto —{@code secretData} Keycloak no lo
 * devuelve nunca—, pero tampoco es asunto de quien administra usuarios, así que se queda fuera.</p>
 *
 * @param type el nombre de Keycloak para la clase de credencial: {@code password}, {@code otp},
 *             {@code webauthn}, {@code webauthn-passwordless}...
 * @param userLabel el nombre que le puso la propia persona al enrolarla, si le puso alguno
 */
public record UserCredentialResponse(
        String id,
        String type,
        String userLabel,
        Instant createdAt
) {
}
