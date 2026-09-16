package com.alejandro.mtousers;

import com.alejandro.mtousers.keycloak.KeycloakAdminGateway;
import com.alejandro.mtousers.service.ProfileService;
import com.alejandro.mtousers.service.RoleService;
import com.alejandro.mtousers.service.UserService;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El contexto entero, sin Keycloak escuchando: ni el JWK Set del resource server ni el token de la
 * cuenta de servicio se piden al arrancar, así que un Keycloak caído no puede impedir el arranque.
 */
@SpringBootTest
@ActiveProfiles("test")
class MtoUsersApplicationTests {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoadsWithoutKeycloakListening() {
        assertNotNull(context.getBean(Keycloak.class));
        assertNotNull(context.getBean(KeycloakAdminGateway.class));
        assertNotNull(context.getBean(UserService.class));
        assertNotNull(context.getBean(RoleService.class));
        assertNotNull(context.getBean(ProfileService.class));
    }

    @Test
    void exactlyOneSecurityFilterChainIsRegistered() {
        assertEquals(1, context.getBeansOfType(SecurityFilterChain.class).size());
    }

    /**
     * El admin client arrastra Jackson 2 para su uso interno. El JSON de la API tiene que seguir
     * saliendo por Jackson 3, que es el de Spring MVC en Boot 4: si Boot registrara el conversor
     * JSON de Jackson 2, el {@code ProblemDetail} y los records se serializarían con otras reglas.
     * (El conversor YAML de Jackson 2 que aparece en la lista lo trae springdoc para
     * {@code /v3/api-docs.yaml}, igual que en el resto de servicios; no sirve JSON.)
     */
    @Test
    void theApiJsonIsSerializedWithJackson3AndNotWithTheJackson2ThatKeycloakBrings() {
        List<HttpMessageConverter<?>> converters = context.getBean(RequestMappingHandlerAdapter.class).getMessageConverters();

        assertTrue(converters.stream().anyMatch(JacksonJsonHttpMessageConverter.class::isInstance));
        assertFalse(converters.stream().anyMatch(converter -> converter.getClass().getSimpleName().equals("MappingJackson2HttpMessageConverter")),
                "Ningún conversor JSON de Jackson 2 para la API: " + converters);
    }
}
