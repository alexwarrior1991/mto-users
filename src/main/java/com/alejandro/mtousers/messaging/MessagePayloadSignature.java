package com.alejandro.mtousers.messaging;

import com.alejandro.mtousers.configuration.messaging.MessageSignatureProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Signs the bytes that really travel, as {@code mto-configuration} does ({@code MessagePayloadSignature}
 * there): the consumer signs the bytes it received and compares, without deserializing anything.
 * With a secret it is HMAC-SHA256 and protects against tampering; without one it is a plain
 * SHA-256, which detects corruption but not tampering, and the startup log says so.
 */
public class MessagePayloadSignature {

    public static final String HEADER_SIGNATURE = "messageSignature";
    public static final String HEADER_SIGNATURE_ALGORITHM = "messageSignatureAlgorithm";

    private static final Logger log = LoggerFactory.getLogger(MessagePayloadSignature.class);
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String DIGEST_ALGORITHM = "SHA-256";

    private final MessageSignatureProperties properties;

    public MessagePayloadSignature(MessageSignatureProperties properties) {
        this.properties = properties;
        if (!properties.hasSecret()) {
            log.info("Messaging without a signature secret: messages are signed with plain {}, which detects "
                    + "corruption but not tampering. Set app.messaging.signature.secret to use HMAC.", DIGEST_ALGORITHM);
        }
    }

    /** The algorithm's name, as it travels in the header. */
    public String algorithm() {
        return properties.hasSecret() ? "HMAC-SHA256" : DIGEST_ALGORITHM;
    }

    public String sign(byte[] payload) {
        if (payload == null) {
            throw new IllegalArgumentException("The payload to sign cannot be null");
        }
        return properties.hasSecret() ? hmac(payload) : digest(payload);
    }

    /** Constant-time comparison: comparing signatures with equals leaks information. */
    public boolean verify(byte[] payload, String signature) {
        if (payload == null || signature == null) {
            return false;
        }
        return MessageDigest.isEqual(sign(payload).getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
    }

    private String hmac(byte[] payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(properties.getSecret().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(payload));
        } catch (Exception e) {
            throw new IllegalStateException("Error signing the message", e);
        }
    }

    private String digest(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(DIGEST_ALGORITHM).digest(payload));
        } catch (Exception e) {
            throw new IllegalStateException("Error hashing the message", e);
        }
    }
}
