package com.alejandro.mtousers.messaging;

import com.alejandro.mtousers.configuration.security.CurrentUserService;
import com.alejandro.mtousers.configuration.web.CorrelationIdFilter;
import com.alejandro.mtousers.service.AdminAuditLog.AdminAction;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Builds the envelope of an event in the thread that knows the request: the actor comes from the
 * {@code SecurityContext} and the correlation id from the MDC, where {@code CorrelationIdFilter}
 * left it. Nothing here touches the broker.
 *
 * <p>{@code values} always starts with {@code targetUserId} and {@code targetUsername}: whoever the
 * event is about is what the consumer needs to route a notice to the right people and to throttle
 * several actions on the same user into one.</p>
 */
public class UsersEventEnvelopeFactory {

    private final CurrentUserService currentUser;
    private final ObjectMapper objectMapper;
    private final String origin;
    private final Clock clock;

    public UsersEventEnvelopeFactory(CurrentUserService currentUser, ObjectMapper objectMapper, String origin) {
        this(currentUser, objectMapper, origin, Clock.systemUTC());
    }

    UsersEventEnvelopeFactory(CurrentUserService currentUser, ObjectMapper objectMapper, String origin, Clock clock) {
        this.currentUser = currentUser;
        this.objectMapper = objectMapper;
        this.origin = origin;
        this.clock = clock;
    }

    public AsynchronousMessage<DomainEvent> create(AdminAction action, String targetUserId, String targetUsername,
                                                   Map<String, Object> detail) {
        return create(UUID.randomUUID(), action, targetUserId, targetUsername, detail);
    }

    public AsynchronousMessage<DomainEvent> create(UUID operationId, AdminAction action, String targetUserId,
                                                   String targetUsername, Map<String, Object> detail) {
        SensitiveKeys.assertNone(detail);

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("targetUserId", targetUserId);
        values.put("targetUsername", targetUsername);
        if (detail != null) {
            values.putAll(detail);
        }

        UsersEventNames.EventName name = UsersEventNames.of(action);
        DomainEvent data = new DomainEvent(name.entityName(), targetUserId, name.eventName(), values);

        AsynchronousMessage<DomainEvent> message = new AsynchronousMessage<>(
                operationId,
                "user-" + targetUserId,
                origin,
                Instant.now(clock),
                UsersEventNames.eventType(name),
                data,
                "PENDING",
                currentActor(),
                currentCorrelationId());

        return message.withMessageHash(hash(message));
    }

    /** Never null: what has no user is said as {@code SYSTEM} instead of kept quiet. */
    MessageActor currentActor() {
        Optional<Authentication> authentication = currentUser.getAuthentication();
        if (authentication.isEmpty()) {
            return MessageActor.system();
        }
        String username = currentUser.getUsername().orElseGet(() -> authentication.get().getName());
        return MessageActor.of(currentUser.getUserId().orElse(null), username);
    }

    static String currentCorrelationId() {
        String value = MDC.get(CorrelationIdFilter.MDC_KEY);
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * The fingerprint of the seven original keys, computed exactly as {@code mto-configuration}
     * computes it: SHA-256 over the JSON of those keys before serializing the envelope. It is not a
     * signature and no consumer verifies it; the signature travels in a header, over the bytes.
     */
    private String hash(AsynchronousMessage<DomainEvent> message) {
        try {
            String raw = objectMapper.writeValueAsString(new HashSource(
                    message.operationId(), message.referenceId(), message.origin(),
                    message.creationDate(), message.eventType(), message.data()));
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Error calculating the message hash", e);
        }
    }

    private record HashSource(UUID operationId, String referenceId, String origin, Instant creationDate,
                              String eventType, DomainEvent data) {
    }
}
