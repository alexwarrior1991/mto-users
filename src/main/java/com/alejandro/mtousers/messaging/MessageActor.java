package com.alejandro.mtousers.messaging;

import java.util.Objects;

/**
 * The {@code actor} of the envelope: who asked for what the event tells. This is the whole point
 * of publishing an event from here instead of relying on Keycloak's admin events, where the author
 * is always the service account {@code mto-users-svc}, never the person.
 *
 * @param id       the {@code sub} of the token; null without a user
 * @param username the {@code preferred_username}; null without a user
 * @param kind     how this service classified it
 */
public record MessageActor(String id, String username, MessageActorKind kind) {

    /** Prefix with which Keycloak names the user of a service account. */
    public static final String SERVICE_ACCOUNT_PREFIX = "service-account-";

    public MessageActor {
        Objects.requireNonNull(kind, "kind is required");
    }

    public static MessageActor system() {
        return new MessageActor(null, null, MessageActorKind.SYSTEM);
    }

    /** An authenticated user, classified by name: a Keycloak service account is always {@code service-account-<client>}. */
    public static MessageActor of(String id, String username) {
        boolean service = username != null && username.startsWith(SERVICE_ACCOUNT_PREFIX);
        return new MessageActor(id, username, service ? MessageActorKind.SERVICE : MessageActorKind.PERSON);
    }
}
