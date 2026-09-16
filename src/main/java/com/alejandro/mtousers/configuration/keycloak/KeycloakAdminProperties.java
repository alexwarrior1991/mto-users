package com.alejandro.mtousers.configuration.keycloak;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * Acceso a la Admin API de Keycloak ({@code app.keycloak.*}).
 *
 * @param authServerUrl     raíz de Keycloak, sin realm ({@code http://auth.mto.local:8082})
 * @param realm             realm que se administra y en el que vive la cuenta de servicio
 * @param adminClientId     cliente confidencial con cuenta de servicio ({@code mto-users-svc})
 * @param adminClientSecret su secreto; vacío deja arrancar (el token se pide en la primera llamada)
 *                          y hace fallar esa llamada con un 502 que dice por qué
 * @param connectTimeout    tiempo máximo para abrir la conexión
 * @param readTimeout       tiempo máximo esperando la respuesta
 * @param poolSize          conexiones simultáneas hacia Keycloak
 * @param protectedClients  clientes cuyos roles esta API se niega a listar y a asignar. La cuenta
 *                          de servicio tiene {@code manage-users}, que en Keycloak permite mapear
 *                          cualquier rol; esta lista es lo que impide que un {@code users-roles-write}
 *                          se convierta en administrador del realm concediéndose
 *                          {@code realm-management/realm-admin}
 */
@Validated
@ConfigurationProperties(prefix = "app.keycloak")
public record KeycloakAdminProperties(
        @NotBlank String authServerUrl,
        @NotBlank String realm,
        @NotBlank String adminClientId,
        String adminClientSecret,
        @DefaultValue("2s") @NotNull Duration connectTimeout,
        @DefaultValue("10s") @NotNull Duration readTimeout,
        @DefaultValue("10") @Min(1) int poolSize,
        @DefaultValue({"realm-management", "broker", "account", "account-console", "admin-cli", "security-admin-console"})
        List<String> protectedClients
) {

    public KeycloakAdminProperties {
        authServerUrl = authServerUrl == null ? null : stripTrailingSlash(authServerUrl);
        protectedClients = protectedClients == null ? List.of() : List.copyOf(protectedClients);
    }

    /** Raíz de los recursos de administración del realm. */
    public String adminRealmUrl() {
        return authServerUrl + "/admin/realms/" + realm;
    }

    public boolean isProtectedClient(String clientId) {
        return clientId != null && protectedClients.contains(clientId);
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
