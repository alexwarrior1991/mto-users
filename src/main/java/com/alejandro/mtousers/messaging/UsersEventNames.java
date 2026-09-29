package com.alejandro.mtousers.messaging;

import com.alejandro.mtousers.service.AdminAuditLog.AdminAction;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * The names that make up the contract with {@code mto-notification}: the exchange, and for each
 * administrative action the entity, the event, the routing key and the {@code eventType}.
 *
 * <p>The routing key is {@code mto.users.<entity>.<event>}, and the consumer derives the activity
 * type from it ({@code users.user.created}, {@code users.session.all-revoked}...). Changing a name
 * here changes what the consumer stores and what its rules match, so every value is fixed by
 * {@code MessagingLayerTest}.</p>
 */
public final class UsersEventNames {

    public static final String EXCHANGE = "mto.users.exchange";
    public static final String ROUTING_PREFIX = "mto.users";
    public static final String ROUTING_PATTERN = "mto.users.#";

    private static final String EVENT_TYPE_PREFIX = "USERS";

    private static final Map<AdminAction, EventName> NAMES = new EnumMap<>(AdminAction.class);

    static {
        NAMES.put(AdminAction.USER_CREATED, new EventName("user", "created"));
        NAMES.put(AdminAction.USER_UPDATED, new EventName("user", "updated"));
        NAMES.put(AdminAction.USER_ENABLED, new EventName("user", "enabled"));
        NAMES.put(AdminAction.USER_DISABLED, new EventName("user", "disabled"));
        NAMES.put(AdminAction.USER_DELETED, new EventName("user", "deleted"));
        NAMES.put(AdminAction.PASSWORD_RESET, new EventName("user", "password-reset"));
        NAMES.put(AdminAction.ACTIONS_EMAIL_SENT, new EventName("user", "actions-email-sent"));
        NAMES.put(AdminAction.CLIENT_ROLES_ADDED, new EventName("client-roles", "added"));
        NAMES.put(AdminAction.CLIENT_ROLES_REMOVED, new EventName("client-roles", "removed"));
        NAMES.put(AdminAction.PROFILE_ASSIGNED, new EventName("profile", "assigned"));
        NAMES.put(AdminAction.PROFILE_REMOVED, new EventName("profile", "removed"));
        NAMES.put(AdminAction.SESSION_REVOKED, new EventName("session", "revoked"));
        NAMES.put(AdminAction.ALL_SESSIONS_REVOKED, new EventName("session", "all-revoked"));
        NAMES.put(AdminAction.OFFLINE_SESSION_REVOKED, new EventName("offline-session", "revoked"));
        NAMES.put(AdminAction.ALL_OFFLINE_SESSIONS_REVOKED, new EventName("offline-session", "all-revoked"));
        NAMES.put(AdminAction.CREDENTIAL_DELETED, new EventName("credential", "deleted"));
    }

    private UsersEventNames() {
    }

    /** The entity and the event of an action. Every action has one: an unmapped action is a bug, not a silent skip. */
    public static EventName of(AdminAction action) {
        EventName name = NAMES.get(action);
        if (name == null) {
            throw new IllegalStateException("Admin action without an event name: " + action);
        }
        return name;
    }

    /** {@code mto.users.<entity>.<event>}: what decides which queues receive the message. */
    public static String routingKey(EventName name) {
        return ROUTING_PREFIX + "." + name.entityName() + "." + name.eventName();
    }

    /** {@code USERS_<ENTITY>_<EVENT>}: the {@code eventType} of the envelope and of the header. */
    public static String eventType(EventName name) {
        return EVENT_TYPE_PREFIX + "_" + constant(name.entityName()) + "_" + constant(name.eventName());
    }

    private static String constant(String value) {
        return value.toUpperCase(Locale.ROOT).replace('-', '_');
    }

    /** What the event is called, in the two halves the routing key joins. */
    public record EventName(String entityName, String eventName) {
    }
}
