package com.alejandro.mtousers.configuration.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * Metadatos, esquema de seguridad, etiquetas y respuestas de error reutilizables del OpenAPI.
 *
 * <p>Las rutas no se declaran aquí a mano: springdoc las lee de las anotaciones de los
 * controladores, que son la única fuente de verdad de la API. Lo que sí se fija aquí es lo
 * transversal, que ningún controlador debería repetir.</p>
 */
@Configuration
public class OpenApiDocumentationConfiguration {

    private static final String PROBLEM_JSON = "application/problem+json";
    static final String BEARER_AUTH = "bearerAuth";
    static final String PROBLEM_SCHEMA = "ProblemDetail";

    @Bean
    public OpenAPI mtoUsersOpenApi() {
        return new OpenAPI()
                .info(apiInfo())
                // Requisito global: cada operación documentada pide el bearer salvo que lo anule
                // explícitamente, de modo que añadir un endpoint nuevo no lo publica como abierto.
                .security(List.of(new SecurityRequirement().addList(BEARER_AUTH)))
                .servers(List.of(
                        new Server().url("http://localhost:8084").description("Local development server"),
                        new Server().url("http://localhost:8090/api/users").description("Through mto-gateway")
                ))
                .tags(tags())
                .components(components());
    }

    private static Info apiInfo() {
        return new Info()
                .title("MTO Users API")
                .description("Administration of the users, client roles and profiles of the MTO realm. "
                        + "Keycloak is the source of truth: nothing is stored here. Profiles are the "
                        + "composite realm roles of the platform (mto-*), expanded by Keycloak into the "
                        + "client roles of every service when a token is issued.")
                .version("v1")
                .contact(new Contact()
                        .name("MTO Platform Team")
                        .email("support@example.com"))
                .license(new License()
                        .name("Proprietary"));
    }

    private static List<Tag> tags() {
        return List.of(
                tag("Users", "Keycloak users of the realm: search, create, update, enable/disable, delete, temporary password and required actions."),
                tag("Roles", "Client roles: catalogue per client and assignment to users."),
                tag("Profiles", "Profiles (composite realm roles): catalogue, roles they include and assignment to users.")
        );
    }

    private static Components components() {
        return new Components()
                .addSecuritySchemes(BEARER_AUTH, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Keycloak-issued JWT access token. Send it as \"Authorization: Bearer <token>\"."))
                .addSchemas(PROBLEM_SCHEMA, problemDetailSchema())
                .addResponses("BadRequest", errorResponse("Invalid request, validation failure, malformed JSON or a role of a protected client.", HttpStatus.BAD_REQUEST))
                .addResponses("Unauthorized", errorResponse("Missing, expired or otherwise invalid bearer token.", HttpStatus.UNAUTHORIZED))
                .addResponses("Forbidden", errorResponse("The authenticated user lacks the role required by this operation.", HttpStatus.FORBIDDEN))
                .addResponses("NotFound", errorResponse("The user, client, role or profile was not found in Keycloak.", HttpStatus.NOT_FOUND))
                .addResponses("Conflict", errorResponse("A user with the same username or email already exists.", HttpStatus.CONFLICT))
                .addResponses("BadGateway", errorResponse("Keycloak answered with an unexpected error, or rejected the service account of this service.", HttpStatus.BAD_GATEWAY))
                .addResponses("ServiceUnavailable", errorResponse("Keycloak did not answer in time.", HttpStatus.SERVICE_UNAVAILABLE))
                .addResponses("InternalServerError", errorResponse("Unexpected server error. Internal details are not exposed to clients.", HttpStatus.INTERNAL_SERVER_ERROR));
    }

    /** RFC 9457 más las propiedades que añade esta API. */
    private static Schema<?> problemDetailSchema() {
        ObjectSchema schema = new ObjectSchema();
        schema.addProperty("type", new StringSchema().description("URN that identifies the kind of error, e.g. urn:problem:mto-users:USR-404"));
        schema.addProperty("title", new StringSchema());
        schema.addProperty("status", new IntegerSchema());
        schema.addProperty("detail", new StringSchema());
        schema.addProperty("instance", new StringSchema().description("Path of the request"));
        schema.addProperty("errorCode", new StringSchema().description("Stable machine-readable code, e.g. USR-404, USR-409, KC-503"));
        schema.addProperty("correlationId", new StringSchema().description("Value of X-Correlation-Id, to find the request in the logs"));
        schema.addProperty("timestamp", new StringSchema().format("date-time"));
        schema.addProperty("validationErrors", new io.swagger.v3.oas.models.media.ArraySchema()
                .items(new ObjectSchema()
                        .addProperty("field", new StringSchema())
                        .addProperty("message", new StringSchema())));
        return schema;
    }

    private static Tag tag(String name, String description) {
        return new Tag().name(name).description(description);
    }

    private static ApiResponse errorResponse(String description, HttpStatus status) {
        return new ApiResponse()
                .description(status.value() + " - " + description)
                .content(new Content().addMediaType(PROBLEM_JSON, new MediaType()
                        .schema(new Schema<>().$ref("#/components/schemas/" + PROBLEM_SCHEMA))));
    }
}
