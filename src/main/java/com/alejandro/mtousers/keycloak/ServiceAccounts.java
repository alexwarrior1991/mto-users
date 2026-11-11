package com.alejandro.mtousers.keycloak;

import org.keycloak.representations.idm.UserRepresentation;

import java.util.Locale;

/**
 * Cómo se reconoce la cuenta de servicio de un cliente entre los usuarios del realm.
 *
 * <p>Keycloak la crea como un usuario más, llamado {@code service-account-<clientId>}. La
 * representación tiene un campo {@code serviceAccountClientId}, pero la Admin API <b>no lo
 * devuelve</b> al leer o buscar usuarios (comprobado contra Keycloak 26.1: solo sale en la
 * exportación del realm), así que lo que de verdad decide es el nombre. El campo se mira igualmente,
 * por si una versión posterior lo trae.</p>
 *
 * <p>Para que el nombre sea una señal fiable, el prefijo queda reservado: esta API no crea usuarios
 * que empiecen por él, y nunca cambia el nombre de uno que existe.</p>
 */
public final class ServiceAccounts {

    /** {@code ServiceAccountConstants.SERVICE_ACCOUNT_USER_PREFIX} de Keycloak. */
    public static final String USERNAME_PREFIX = "service-account-";

    private ServiceAccounts() {
    }

    public static boolean isServiceAccount(UserRepresentation user) {
        return user != null && (user.getServiceAccountClientId() != null || isReservedUsername(user.getUsername()));
    }

    /** Keycloak guarda los nombres en minúsculas, así que la comparación no distingue mayúsculas. */
    public static boolean isReservedUsername(String username) {
        return username != null && username.strip().toLowerCase(Locale.ROOT).startsWith(USERNAME_PREFIX);
    }
}
