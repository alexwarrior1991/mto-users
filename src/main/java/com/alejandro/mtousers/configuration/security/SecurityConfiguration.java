package com.alejandro.mtousers.configuration.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Cadena de filtros de la API. La aplicación es un <em>resource server</em>: no emite tokens ni
 * guarda usuarios, solo valida los JWT que emite Keycloak y traduce sus roles a autoridades.
 *
 * <p>Que este servicio administre usuarios de Keycloak no cambia nada aquí: lo hace con la cuenta
 * de servicio {@code mto-users-svc} por la Admin API (ver {@code KeycloakAdminClientConfiguration}),
 * y quien llama a esta API se autentica exactamente igual que en el resto de servicios del
 * dominio.</p>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfiguration {

    /** Raíz de los recursos de negocio. Coincide con el {@code @RequestMapping} de cada controlador. */
    public static final String API = "/api/v1/users";

    private static final String[] API_DOCS = {
            "/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml",
            "/swagger-ui/**", "/swagger-ui.html"
    };

    private final SecurityProperties securityProperties;
    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;

    public SecurityConfiguration(
            SecurityProperties securityProperties,
            RestAuthenticationEntryPoint authenticationEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler
    ) {
        this.securityProperties = securityProperties;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
    }

    @Bean
    public KeycloakJwtAuthenticationConverter jwtAuthenticationConverter() {
        return new KeycloakJwtAuthenticationConverter(securityProperties);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            KeycloakJwtAuthenticationConverter jwtAuthenticationConverter
    ) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptionHandling -> exceptionHandling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )
                .authorizeHttpRequests(authorize -> {
                    authorize.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll();

                    // Las sondas de arranque y de vida las consulta el orquestador, que no tiene
                    // token. El resto del detalle de health lo gobierna
                    // 'management.endpoint.health.show-details'.
                    authorize.requestMatchers(
                            "/actuator/health",
                            "/actuator/health/**",
                            "/actuator/info"
                    ).permitAll();

                    // La documentación describe la superficie completa de la API. En un entorno
                    // desplegado es el mejor mapa posible para quien busque un hueco, así que se
                    // abre solo donde la configuración lo pide.
                    if (securityProperties.exposeApiDocs()) {
                        authorize.requestMatchers(API_DOCS).permitAll();
                    }

                    // Todo Actuator cerrado salvo health e info. Lo que modifica va antes que la
                    // regla general y con su propio permiso: en Actuator las @WriteOperation viajan
                    // por POST y las @DeleteOperation por DELETE; el resto es lectura.
                    authorize.requestMatchers(HttpMethod.POST, "/actuator/**").hasRole(SecurityRoles.OPS_WRITE);
                    authorize.requestMatchers(HttpMethod.DELETE, "/actuator/**").hasRole(SecurityRoles.OPS_WRITE);
                    authorize.requestMatchers("/actuator/**").hasRole(SecurityRoles.OPS_METRICS);

                    // El orden importa: cada petición se resuelve con la primera regla que encaja,
                    // así que lo concreto va antes que lo general. Cada permiso cubre una sola
                    // cosa y ninguno implica otro: poder dar de alta usuarios no da derecho a
                    // repartir roles, ni a fijar contraseñas, ni a borrar.
                    authorize.requestMatchers(HttpMethod.PUT, API + "/*/roles/**").hasRole(SecurityRoles.USERS_ROLES_WRITE);
                    authorize.requestMatchers(HttpMethod.DELETE, API + "/*/roles/**").hasRole(SecurityRoles.USERS_ROLES_WRITE);
                    authorize.requestMatchers(HttpMethod.PUT, API + "/*/profiles/**").hasRole(SecurityRoles.USERS_PROFILES_WRITE);
                    authorize.requestMatchers(HttpMethod.DELETE, API + "/*/profiles/**").hasRole(SecurityRoles.USERS_PROFILES_WRITE);
                    authorize.requestMatchers(HttpMethod.POST, API + "/*/reset-password").hasRole(SecurityRoles.USERS_PASSWORD_RESET);
                    authorize.requestMatchers(HttpMethod.DELETE, API + "/*").hasRole(SecurityRoles.USERS_DELETE);

                    authorize.requestMatchers(HttpMethod.GET, API + "/**").hasRole(SecurityRoles.USERS_READ);
                    // HEAD lo sirve el mismo handler que GET y revela si un recurso existe, así que
                    // no puede quedarse en el 'anyRequest().authenticated()' del final.
                    authorize.requestMatchers(HttpMethod.HEAD, API + "/**").hasRole(SecurityRoles.USERS_READ);
                    // Alta, modificación, habilitar/deshabilitar y envío de acciones requeridas.
                    authorize.requestMatchers(HttpMethod.POST, API + "/**").hasRole(SecurityRoles.USERS_WRITE);
                    authorize.requestMatchers(HttpMethod.PUT, API + "/**").hasRole(SecurityRoles.USERS_WRITE);
                    authorize.requestMatchers(HttpMethod.PATCH, API + "/**").hasRole(SecurityRoles.USERS_WRITE);

                    authorize.anyRequest().authenticated();
                })
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                )
                .build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        SecurityProperties.Cors corsProperties = securityProperties.cors();

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(corsProperties.allowedOrigins());
        configuration.setAllowedMethods(corsProperties.allowedMethods());
        configuration.setAllowedHeaders(corsProperties.allowedHeaders());
        configuration.setExposedHeaders(corsProperties.exposedHeaders());
        configuration.setAllowCredentials(corsProperties.allowCredentials());
        configuration.setMaxAge(corsProperties.maxAge());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /**
     * Se construye con el JWK Set en lugar de con el descubrimiento por issuer: {@code
     * JwtDecoders.fromIssuerLocation()} hace una llamada HTTP bloqueante al crear el bean, de modo
     * que la aplicación no arranca si Keycloak todavía no sirve el {@code
     * .well-known/openid-configuration}. Con el JWK Set la descarga es perezosa —la primera vez que
     * llega un token— y un reinicio simultáneo de los dos servicios deja de ser un fallo de
     * arranque.
     */
    @Bean
    public JwtDecoder jwtDecoder(OAuth2ResourceServerProperties properties) {
        OAuth2ResourceServerProperties.Jwt jwtProperties = properties.getJwt();
        String issuerUri = jwtProperties.getIssuerUri();

        NimbusJwtDecoder jwtDecoder = NimbusJwtDecoder
                .withJwkSetUri(resolveJwkSetUri(jwtProperties))
                .build();

        // Se parte del validador por defecto en vez de reemplazarlo: incluye la comprobación de
        // emisor y de vigencia, y hereda las que Spring Security añada en versiones futuras.
        jwtDecoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuerUri),
                audienceValidator()
        ));

        return jwtDecoder;
    }

    /**
     * Keycloak publica el JWK Set en una ruta fija bajo el realm. Se respeta {@code jwk-set-uri} si
     * está configurado, para no atar la aplicación a esa convención.
     */
    static String resolveJwkSetUri(OAuth2ResourceServerProperties.Jwt jwtProperties) {
        if (StringUtils.hasText(jwtProperties.getJwkSetUri())) {
            return jwtProperties.getJwkSetUri();
        }

        return jwtProperties.getIssuerUri() + "/protocol/openid-connect/certs";
    }

    /**
     * Que {@code required-audience} esté relleno lo garantiza {@link SecurityProperties} en el
     * arranque, así que aquí no hay ninguna rama que deje pasar el token por falta de
     * configuración: o se valida la audiencia, o se ha desactivado de forma explícita.
     */
    private OAuth2TokenValidator<Jwt> audienceValidator() {
        if (!securityProperties.audienceValidationEnabled()) {
            return jwt -> OAuth2TokenValidatorResult.success();
        }

        return new JwtAudienceValidator(securityProperties.requiredAudience());
    }
}
