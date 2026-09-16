package com.alejandro.mtousers.dto;

/**
 * Acciones requeridas de Keycloak que esta API permite pedir por correo o fijar al crear un usuario.
 * Es un subconjunto cerrado a propósito: el nombre viaja tal cual a Keycloak, y un enum evita que
 * un cliente mande cualquier cadena.
 */
public enum RequiredAction {
    UPDATE_PASSWORD,
    VERIFY_EMAIL,
    UPDATE_PROFILE,
    CONFIGURE_TOTP,
    TERMS_AND_CONDITIONS
}
