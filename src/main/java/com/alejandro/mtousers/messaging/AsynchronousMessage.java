package com.alejandro.mtousers.messaging;

import java.time.Instant;
import java.util.UUID;

/**
 * The common envelope of the domain, as {@code mto-configuration} defines it ({@code AsynchronousMessage}
 * there): the seven original keys plus {@code actor} and {@code correlationId}. {@code messageHash}
 * is the fingerprint of the seven original keys only, as in every producer.
 *
 * @param operationId   the idempotency key of the event in the consumers' inbox
 * @param referenceId   a readable reference of the aggregate ({@code user-<id>})
 * @param origin        this service's name
 * @param creationDate  when the event was built, in the request thread
 * @param eventType     {@code USERS_USER_CREATED}...
 * @param data          the payload
 * @param messageHash   SHA-256 of the seven original keys; a fingerprint, not a signature
 * @param actor         who asked for the operation, classified by this service
 * @param correlationId the {@code X-Correlation-Id} of the request that caused the event
 */
public record AsynchronousMessage<T>(
        UUID operationId,
        String referenceId,
        String origin,
        Instant creationDate,
        String eventType,
        T data,
        String messageHash,
        MessageActor actor,
        String correlationId
) {

    public AsynchronousMessage<T> withMessageHash(String hash) {
        return new AsynchronousMessage<>(operationId, referenceId, origin, creationDate, eventType, data, hash, actor, correlationId);
    }
}
