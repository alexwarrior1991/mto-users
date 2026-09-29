package com.alejandro.mtousers.messaging;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * The last guard before a value leaves this service in an event: no key that smells like a
 * credential goes into {@code values}. The services never put one there, but the rule is worth a
 * check at the only door, because an event lives for days in queues, inboxes and an activity log
 * that many people read.
 */
public final class SensitiveKeys {

    private static final Pattern SENSITIVE = Pattern.compile("(?i)password|passwd|secret|token|credentialdata|otp");

    private SensitiveKeys() {
    }

    public static void assertNone(Map<String, ?> values) {
        if (values == null) {
            return;
        }
        for (String key : values.keySet()) {
            if (key != null && SENSITIVE.matcher(key).find()) {
                throw new IllegalArgumentException("A value named '" + key + "' cannot travel in an event");
            }
        }
    }
}
