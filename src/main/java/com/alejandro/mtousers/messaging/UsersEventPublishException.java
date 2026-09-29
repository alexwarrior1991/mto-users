package com.alejandro.mtousers.messaging;

/** One attempt to hand an event to the broker did not end with its ack: nack, unroutable, timeout or a connection failure. */
public class UsersEventPublishException extends RuntimeException {

    public UsersEventPublishException(String message) {
        super(message);
    }

    public UsersEventPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
