package com.alejandro.mtousers.messaging;

import com.alejandro.mtousers.service.AdminAuditLog.AdminAction;

import java.util.Map;

/**
 * The only way an administrative action leaves this service as an event. Called by
 * {@code AdminAuditLog} once per action, after Keycloak has answered, so nothing is announced that
 * did not happen.
 *
 * <p>Publishing is best effort by design: this service has no database, so there is no outbox and
 * no transaction to tie the event to. What the implementation guarantees is that the HTTP response
 * never waits for the broker, that a broker hiccup is retried a few times, and that a lost event is
 * loud (a WARN line and a counter), never silent. What it cannot guarantee is delivery: the event
 * of an action done while the broker was down for longer than the retries is lost, and Keycloak's
 * own admin event, read by {@code mto-notification}, is what remains of it.</p>
 */
public interface UsersEventPublisher {

    /**
     * @param action         what was done
     * @param targetUserId   the user it was done to; the aggregate of the event
     * @param targetUsername their username when the service already had it, null otherwise
     * @param detail         what else is worth telling; never a password, a token or a secret
     */
    void publish(AdminAction action, String targetUserId, String targetUsername, Map<String, Object> detail);
}
