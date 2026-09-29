package com.alejandro.mtousers.service;

import com.alejandro.mtousers.configuration.security.CurrentUserService;
import com.alejandro.mtousers.messaging.UsersEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Rastro de cada operación administrativa: quién hizo qué sobre quién. Sale por el log con un
 * logger propio ({@code com.alejandro.mtousers.audit}) para poder enrutarlo aparte, y con el
 * identificador de correlación que el patrón de log toma del MDC; y sale también como evento hacia
 * {@code mto-notification} ({@link UsersEventPublisher}), con la misma información y la persona
 * que lo hizo, que es lo que los eventos de administración de Keycloak no saben decir.
 *
 * <p>Es el único gancho de las dieciséis acciones: cada servicio llama aquí una vez, después de que
 * Keycloak haya respondido, y de aquí salen la línea y el evento. Nada se anuncia que no haya
 * pasado, y nada pasa sin anunciarse.</p>
 *
 * <p>Aquí no entra nunca una contraseña ni un token: el detalle son nombres de roles, perfiles o
 * acciones, y los DTOs que llevan contraseña la ocultan en su {@code toString()} por si acaso. El
 * publicador rechaza además cualquier clave del detalle que huela a credencial.</p>
 */
@Component
public class AdminAuditLog {

    private static final Logger AUDIT = LoggerFactory.getLogger("com.alejandro.mtousers.audit");

    /** Quien llama sin usuario en el contexto —no debería pasar tras la cadena de seguridad— queda así. */
    static final String UNKNOWN_ACTOR = "unknown";

    private final CurrentUserService currentUser;
    private final UsersEventPublisher events;

    public AdminAuditLog(CurrentUserService currentUser, UsersEventPublisher events) {
        this.currentUser = currentUser;
        this.events = events;
    }

    /**
     * Una línea y un evento por acción, en ese orden.
     *
     * @param targetUsername el nombre del usuario objetivo cuando el servicio ya lo tenía; {@code null}
     *                       si solo conoce el id
     * @param detail         lo que merece contarse de la acción, en el orden en que se quiera leer;
     *                       viaja tal cual en el evento y se imprime como {@code clave=valor}
     */
    public void record(AdminAction action, String targetUserId, String targetUsername, Map<String, Object> detail) {
        Map<String, Object> values = detail == null ? Map.of() : detail;

        AUDIT.info("action={} actor={} actorId={} targetUserId={} targetUsername={} detail={}",
                action,
                currentUser.getUsername().orElse(UNKNOWN_ACTOR),
                currentUser.getUserId().orElse(UNKNOWN_ACTOR),
                targetUserId,
                targetUsername == null ? "-" : targetUsername,
                render(values));

        events.publish(action, targetUserId, targetUsername, values);
    }

    public void record(AdminAction action, String targetUserId, Map<String, Object> detail) {
        record(action, targetUserId, null, detail);
    }

    static String render(Map<String, Object> values) {
        return values.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(" "));
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
        ALL_SESSIONS_REVOKED,
        OFFLINE_SESSION_REVOKED,
        ALL_OFFLINE_SESSIONS_REVOKED,
        CREDENTIAL_DELETED
    }
}
