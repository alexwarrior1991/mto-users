package com.alejandro.mtousers.messaging;

import com.alejandro.mtousers.service.AdminAuditLog.AdminAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The publisher that runs with a broker: builds the envelope in the request thread (actor and
 * correlation are only there), serializes it, signs the exact bytes, and hands the result to the
 * dispatcher's queue. Everything that can fail because of the broker happens later, on the
 * dispatcher's thread; what can fail here is a bug (an unmapped action, a sensitive key), and a
 * bug must fail the request loudly rather than lose events quietly.
 */
public class RabbitUsersEventPublisher implements UsersEventPublisher {

    public static final String HEADER_EVENT_TYPE = "eventType";
    public static final String HEADER_AGGREGATE_TYPE = "aggregateType";
    public static final String HEADER_AGGREGATE_ID = "aggregateId";

    private static final Logger log = LoggerFactory.getLogger(RabbitUsersEventPublisher.class);

    private final UsersEventEnvelopeFactory envelopeFactory;
    private final ObjectMapper objectMapper;
    private final MessagePayloadSignature signature;
    private final String exchange;
    private final UsersEventDispatcher dispatcher;

    public RabbitUsersEventPublisher(UsersEventEnvelopeFactory envelopeFactory, ObjectMapper objectMapper,
                                     MessagePayloadSignature signature, String exchange, UsersEventDispatcher dispatcher) {
        this.envelopeFactory = envelopeFactory;
        this.objectMapper = objectMapper;
        this.signature = signature;
        this.exchange = exchange;
        this.dispatcher = dispatcher;
    }

    @Override
    public void publish(AdminAction action, String targetUserId, String targetUsername, Map<String, Object> detail) {
        AsynchronousMessage<DomainEvent> envelope = envelopeFactory.create(action, targetUserId, targetUsername, detail);
        OutboundMessage message = toOutbound(envelope);

        log.debug("Users event queued: messageId={} routingKey={} actor={}",
                message.messageId(), message.routingKey(), envelope.actor().username());
        dispatcher.dispatch(message);
    }

    OutboundMessage toOutbound(AsynchronousMessage<DomainEvent> envelope) {
        byte[] body = objectMapper.writeValueAsString(envelope).getBytes(StandardCharsets.UTF_8);
        UsersEventNames.EventName name = new UsersEventNames.EventName(envelope.data().entityName(), envelope.data().eventName());

        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put(HEADER_EVENT_TYPE, envelope.eventType());
        headers.put(HEADER_AGGREGATE_TYPE, envelope.data().entityName());
        headers.put(HEADER_AGGREGATE_ID, envelope.data().entityId());
        // The signature goes over the bytes that travel and in a header: a payload cannot carry
        // its own signature, and the consumer verifies it without deserializing anything.
        headers.put(MessagePayloadSignature.HEADER_SIGNATURE, signature.sign(body));
        headers.put(MessagePayloadSignature.HEADER_SIGNATURE_ALGORITHM, signature.algorithm());

        return new OutboundMessage(envelope.operationId().toString(), exchange, UsersEventNames.routingKey(name),
                envelope.eventType(), body, headers);
    }
}
