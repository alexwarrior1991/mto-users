package com.alejandro.mtousers.configuration.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The secret shared with the consumers. With it the signature is an HMAC-SHA256 and protects
 * against tampering; without it, a plain SHA-256 that only detects corruption. Empty by default:
 * sharing a secret between services is an operations decision, and requiring it would stop whoever
 * does not need it from starting. The same value as in {@code mto-configuration}.
 */
@ConfigurationProperties(prefix = "app.messaging.signature")
public record MessageSignatureProperties(String secret) {

    public boolean hasSecret() {
        return secret != null && !secret.isBlank();
    }

    public String getSecret() {
        return secret;
    }
}
