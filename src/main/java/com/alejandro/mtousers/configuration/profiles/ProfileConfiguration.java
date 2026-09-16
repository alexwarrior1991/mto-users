package com.alejandro.mtousers.configuration.profiles;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registra {@link ProfileProperties}. Se hace aquí y no con {@code @ConfigurationPropertiesScan} en
 * la clase principal para que los slices de {@code @WebMvcTest} no arrastren todas las properties de
 * la aplicación, que exigen configuración que esos tests no tienen por qué dar.
 */
@Configuration
@EnableConfigurationProperties(ProfileProperties.class)
public class ProfileConfiguration {
}
