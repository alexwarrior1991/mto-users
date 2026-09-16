package com.alejandro.mtousers.service;

import com.alejandro.mtousers.configuration.security.CurrentUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Rastro de cada operación administrativa: quién hizo qué sobre quién. Sale por el log con un
 * logger propio ({@code com.alejandro.mtousers.audit}) para poder enrutarlo aparte, y con el
 * identificador de correlación que el patrón de log toma del MDC.
 *
 * <p>Aquí no entra nunca una contraseña ni un token: el detalle son nombres de roles, perfiles o
 * acciones, y los DTOs que llevan contraseña la ocultan en su {@code toString()} por si acaso.</p>
 */
@Component
public class AdminAuditLog {

    private static final Logger AUDIT = LoggerFactory.getLogger("com.alejandro.mtousers.audit");

    /** Quien llama sin usuario en el contexto —no debería pasar tras la cadena de seguridad— queda así. */
    static final String UNKNOWN_ACTOR = "unknown";

    private final CurrentUserService currentUser;

    public AdminAuditLog(CurrentUserService currentUser) {
        this.currentUser = currentUser;
    }

    public void record(AdminAction action, String targetUserId, String detail) {
        AUDIT.info("action={} actor={} actorId={} targetUserId={} detail={}",
                action,
                currentUser.getUsername().orElse(UNKNOWN_ACTOR),
                currentUser.getUserId().orElse(UNKNOWN_ACTOR),
                targetUserId,
                detail == null ? "" : detail);
    }

    /** Las operaciones que dejan rastro. Un enum y no cadenas sueltas, para que el log sea filtrable. */
    public enum AdminAction {
        USER_CREATED,
        USER_UPDATED,
        USER_ENABLED,
        USER_DISABLED,
        USER_DELETED,
        PASSWORD_RESET,
        ACTIONS_EMAIL_SENT,
        CLIENT_ROLES_ADDED,
        CLIENT_ROLES_REMOVED,
        PROFILE_ASSIGNED,
        PROFILE_REMOVED,
        SESSION_REVOKED,
        ALL_SESSIONS_REVOKED
    }
}
