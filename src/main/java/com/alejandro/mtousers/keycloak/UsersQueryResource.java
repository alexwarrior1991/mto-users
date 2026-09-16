package com.alejandro.mtousers.keycloak;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.keycloak.representations.idm.UserRepresentation;

import java.util.List;

/**
 * Búsqueda y recuento de usuarios con <b>todos</b> los filtros combinados.
 *
 * <p>{@code UsersResource} del admin client tiene una veintena de sobrecargas de {@code search},
 * pero ninguna combina el texto libre ({@code search}) con {@code enabled}, {@code emailVerified} y
 * {@code q} (los atributos), que es justo lo que pide «los usuarios deshabilitados del departamento
 * de operaciones». El servidor sí lo admite (todos son parámetros del mismo {@code GET /users}),
 * así que se declara aquí la firma que falta y se instancia con {@code Keycloak.proxy(...)}, que le
 * pone el mismo token que al resto. Es el mecanismo de extensión previsto por la biblioteca, no un
 * atajo.</p>
 */
@Produces(MediaType.APPLICATION_JSON)
public interface UsersQueryResource {

    @GET
    List<UserRepresentation> search(@QueryParam("search") String search,
                                    @QueryParam("username") String username,
                                    @QueryParam("email") String email,
                                    @QueryParam("enabled") Boolean enabled,
                                    @QueryParam("emailVerified") Boolean emailVerified,
                                    @QueryParam("q") String attributeQuery,
                                    @QueryParam("first") Integer first,
                                    @QueryParam("max") Integer max,
                                    @QueryParam("briefRepresentation") Boolean briefRepresentation);

    @GET
    @Path("count")
    Integer count(@QueryParam("search") String search,
                  @QueryParam("username") String username,
                  @QueryParam("email") String email,
                  @QueryParam("enabled") Boolean enabled,
                  @QueryParam("emailVerified") Boolean emailVerified,
                  @QueryParam("q") String attributeQuery);
}
