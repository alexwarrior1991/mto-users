package com.alejandro.mtousers.configuration.security;

/**
 * Roles de cliente de Keycloak que comprueba esta API, ya normalizados a autoridad de Spring.
 *
 * <p>Son los nombres que declara {@code keycloak/mto-users-partial-import.json} en mayusculas y con
 * guion bajo: {@code users-read} llega como {@code ROLE_USERS_READ}. Un rol que se anade aqui sin
 * anadirlo alli no lo tiene nadie y todo responde 403.</p>
 */
public final class SecurityRoles {

    private SecurityRoles() {
    }

    /** Consulta de usuarios, de sus roles y perfiles, y de los catalogos de roles y perfiles. */
    public static final String USERS_READ = "USERS_READ";

    /** Alta y modificacion de usuarios, habilitar y deshabilitar, y envio de acciones requeridas. */
    public static final String USERS_WRITE = "USERS_WRITE";

    /** Borrado de usuarios. Aparte de write: es la unica operacion irreversible. */
    public static final String USERS_DELETE = "USERS_DELETE";

    /** Asignar y quitar roles de cliente a un usuario. */
    public static final String USERS_ROLES_WRITE = "USERS_ROLES_WRITE";

    /** Fijar una contrasena temporal a un usuario. */
    public static final String USERS_PASSWORD_RESET = "USERS_PASSWORD_RESET";

    /** Asignar y quitar perfiles (roles compuestos de realm) a un usuario. */
    public static final String USERS_PROFILES_WRITE = "USERS_PROFILES_WRITE";

    /**
     * Cerrar sesiones de un usuario. Aparte de write: expulsa a alguien que esta trabajando, y es
     * lo que se necesita cuando hay que cortar un acceso ya abierto sin tocar nada mas.
     */
    public static final String USERS_SESSIONS_WRITE = "USERS_SESSIONS_WRITE";

    /** Lectura de los endpoints de Actuator. */
    public static final String OPS_METRICS = "OPS_METRICS";

    /** Operaciones de Actuator que modifican estado. */
    public static final String OPS_WRITE = "OPS_WRITE";
}
