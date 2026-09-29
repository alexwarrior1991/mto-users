package com.alejandro.mtousers.messaging;

import java.util.Map;

/**
 * The {@code data} of the events of this service: the same shape {@code mto-configuration} uses for
 * what is not master data ({@code DomainEvent} there), so every consumer reads one form.
 *
 * @param entityName what changed, lowercase with hyphens ({@code user}, {@code session}, {@code profile}...)
 * @param entityId   the target user's id: the user is the aggregate of everything this service does
 * @param eventName  what happened, lowercase with hyphens ({@code created}, {@code password-reset}...)
 * @param values     what is needed to tell it; never a password, a token or a secret
 */
public record DomainEvent(
        String entityName,
        String entityId,
        String eventName,
        Map<String, Object> values
) {
}
