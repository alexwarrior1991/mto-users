package com.alejandro.mtousers.configuration.security;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * No existe un interruptor para apagar la seguridad: lo que cambia entre entornos son estas
 * properties, no la existencia de la cadena de filtros. Un flag de ese tipo tampoco desactivaría
 * nada — con {@code spring-boot-starter-security} en el classpath, quedarse sin
 * {@code SecurityFilterChain} propio devuelve el control a la cadena por defecto de Boot, con
 * formulario de login, CSRF y sesiones.
 *
 * @param clientId                  cliente de Keycloak que representa a esta API ({@code mto-users-api});
 *                                  sus roles de cliente son los permisos que se comprueban
 * @param principalClaim            claim que se usa como nombre del principal y como actor en la auditoria
 * @param audienceValidationEnabled si se exige que el token lleve a esta API en {@code aud}
 * @param requiredAudience          audiencia exigida cuando la validacion esta activa
 * @param exposeApiDocs             publicar swagger-ui y /v3/api-docs sin token
 */
@Validated
@ConfigurationProperties(prefix = "app.security")
public record SecurityProperties(
        @NotBlank String clientId,
        @NotBlank String principalClaim,
        boolean audienceValidationEnabled,
        String requiredAudience,
        boolean exposeApiDocs
) {

    /**
     * Sin esta comprobación, un {@code KEYCLOAK_AUDIENCE} vacío apagaba la validación de audiencia
     * en tiempo de petición y sin dejar rastro en el log: la API pasaba a aceptar cualquier token
     * emitido por el realm, incluido el del frontal de otra aplicación. Mejor no arrancar.
     */
    @AssertTrue(message = "app.security.required-audience es obligatorio cuando "
            + "app.security.audience-validation-enabled es true")
    public boolean isAudienceConfigurationConsistent() {
        return !audienceValidationEnabled || (requiredAudience != null && !requiredAudience.isBlank());
    }
}
