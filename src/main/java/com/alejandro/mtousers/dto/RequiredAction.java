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
    TERMS_AND_CONDITIONS;

    /**
     * Si completar la acción pone o cambia una credencial de la cuenta: la contraseña o el segundo
     * factor. Un enlace por correo con una de ellas deja a quien lo abra fijar esa credencial, que
     * es restablecerla por otro camino, así que pedirlo exige además {@code users-password-reset}.
     *
     * <p>Un {@code switch} sin {@code default} a propósito: una acción nueva (webauthn, códigos de
     * recuperación...) no compila hasta que alguien decide de qué lado cae.</p>
     */
    public boolean touchesCredentials() {
        return switch (this) {
            case UPDATE_PASSWORD, CONFIGURE_TOTP -> true;
            case VERIFY_EMAIL, UPDATE_PROFILE, TERMS_AND_CONDITIONS -> false;
        };
    }
}
