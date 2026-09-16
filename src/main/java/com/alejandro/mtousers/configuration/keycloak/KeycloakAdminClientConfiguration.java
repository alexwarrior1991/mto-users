package com.alejandro.mtousers.configuration.keycloak;

import com.alejandro.mtousers.keycloak.KeycloakAdminClientGateway;
import com.alejandro.mtousers.keycloak.KeycloakAdminGateway;
import jakarta.ws.rs.client.Client;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.JacksonProvider;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.admin.client.spi.ResteasyClientClassicProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * El cliente de la Admin API de Keycloak, con la cuenta de servicio de este servicio.
 *
 * <p>Se usa el cliente oficial ({@code keycloak-admin-client}) y no un {@code RestClient} propio: la
 * superficie que hace falta —usuarios, credenciales, clientes, roles, role-mappings y compuestos—
 * ya viene tipada y la biblioteca gestiona sola el token de {@code client_credentials}, incluida
 * su renovación. Ninguna clase suya sale de {@code keycloak/} y {@code mapper/}: el resto de la
 * aplicación habla con {@link KeycloakAdminGateway}.</p>
 *
 * <p>Crear el bean no toca la red: el token se pide en la primera llamada, así que la aplicación
 * arranca aunque Keycloak no esté listo, igual que el resource server con su JWK Set.</p>
 *
 * <p>El cliente HTTP se construye aquí para fijar los timeouts, que el builder de la biblioteca no
 * expone. Se parte de su propio builder ({@code createClientBuilder()}) y se registra su
 * {@code JacksonProvider}, que es el que tolera campos desconocidos: un Keycloak más nuevo que la
 * biblioteca añade campos a las representaciones, y sin ese proveedor cada respuesta sería un
 * error de deserialización.</p>
 */
@Configuration
@EnableConfigurationProperties(KeycloakAdminProperties.class)
public class KeycloakAdminClientConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(KeycloakAdminClientConfiguration.class);

    @Bean(destroyMethod = "close")
    public Keycloak keycloakAdminClient(KeycloakAdminProperties properties) {
        if (properties.adminClientSecret() == null || properties.adminClientSecret().isBlank()) {
            LOGGER.warn("app.keycloak.admin-client-secret is empty: every call to the Keycloak Admin API will fail "
                    + "until the secret of client '{}' is configured", properties.adminClientId());
        }

        Client client = ResteasyClientClassicProvider.createClientBuilder()
                .register(JacksonProvider.class, 100)
                .connectTimeout(properties.connectTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .readTimeout(properties.readTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .connectionPoolSize(properties.poolSize())
                .build();

        return KeycloakBuilder.builder()
                .serverUrl(properties.authServerUrl())
                .realm(properties.realm())
                .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
                .clientId(properties.adminClientId())
                .clientSecret(properties.adminClientSecret())
                .resteasyClient(client)
                .build();
    }

    @Bean
    public KeycloakAdminGateway keycloakAdminGateway(Keycloak keycloakAdminClient, KeycloakAdminProperties properties) {
        return new KeycloakAdminClientGateway(keycloakAdminClient, properties);
    }
}
