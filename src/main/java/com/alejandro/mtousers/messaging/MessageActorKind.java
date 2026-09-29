package com.alejandro.mtousers.messaging;

/**
 * Who did what an event tells, as this service classifies it when writing the event.
 *
 * <p>The producer classifies, not the consumer: only the producer has the token in front of it. A
 * consumer receives a username and cannot tell whether a person or another service's account was
 * behind it.</p>
 */
public enum MessageActorKind {

    /** A person with a session: the {@code preferred_username} of their token. */
    PERSON,

    /** The service account of another service of the domain, which Keycloak names {@code service-account-<client>}. */
    SERVICE,

    /** Nobody authenticated: a background process of this service. */
    SYSTEM
}
