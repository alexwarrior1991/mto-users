package com.alejandro.mtousers.messaging;

import java.util.Map;

/**
 * An event ready to leave: the bytes that travel, already signed, and where they go. Built entirely
 * in the request thread, so that nothing that depends on the request (the actor, the correlation
 * id, the signature over the exact bytes) is computed later on a thread that has none of it.
 *
 * @param messageId  the {@code operationId} of the envelope, as the AMQP {@code message_id}
 * @param exchange   where it is published
 * @param routingKey {@code mto.users.<entity>.<event>}
 * @param eventType  for the log and the {@code eventType} header
 * @param body       the JSON of the envelope, UTF-8
 * @param headers    {@code eventType}, {@code aggregateType}, {@code aggregateId}, the signature and its algorithm
 */
public record OutboundMessage(
        String messageId,
        String exchange,
        String routingKey,
        String eventType,
        byte[] body,
        Map<String, Object> headers
) {

    public OutboundMessage {
        headers = Map.copyOf(headers);
    }
}
