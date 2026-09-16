package com.alejandro.mtousers.exception;

/**
 * Combinación de filtros que Keycloak no aplica como el cliente espera (400).
 *
 * <p>Existe por un caso concreto: con {@code search} y {@code q} a la vez, Keycloak busca por
 * {@code search} y <b>descarta</b> {@code q} sin decir nada, de modo que una búsqueda por atributo
 * devolvería usuarios que no tienen ese atributo. Antes que responder algo que no se pidió, se
 * rechaza la petición.</p>
 */
public class InvalidSearchException extends UsersException {

    public static final String CODE = "SEARCH-400";

    public InvalidSearchException(String message) {
        super(CODE, message);
    }
}
