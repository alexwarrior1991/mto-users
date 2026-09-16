package com.alejandro.mtousers.dto;

/** Un cliente del realm cuyos roles se pueden asignar. {@code clientId} es el nombre, no el UUID. */
public record ClientResponse(String clientId, String name, String description) {
}
