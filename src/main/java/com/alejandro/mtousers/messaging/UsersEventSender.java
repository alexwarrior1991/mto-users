package com.alejandro.mtousers.messaging;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageBuilderSupport;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * One attempt: publishes a message and <b>waits for the broker's confirm</b>, the same way the
 * outbox relay of {@code mto-configuration} does ({@code OutboxRabbitPublisher.doPublish}).
 *
 * <p>{@code RabbitTemplate.send} alone is fire-and-forget: it returns as soon as the bytes leave
 * the socket, so a broker that dies before persisting the message loses it while this side
 * believes it was sent. Two silent failures are checked: the <b>nack</b> (the broker refused it)
 * and the <b>return</b> (with {@code mandatory=true}, a message no queue matches comes back
 * instead of being dropped; the basic.return always arrives before the ack, which is why it is
 * checked after waiting for the confirm).</p>
 */
public class UsersEventSender {

    private final RabbitTemplate rabbitTemplate;
    private final Duration confirmTimeout;

    public UsersEventSender(RabbitTemplate rabbitTemplate, Duration confirmTimeout) {
        this.rabbitTemplate = rabbitTemplate;
        this.confirmTimeout = confirmTimeout;
    }

    /** @throws UsersEventPublishException when the broker did not ack the message */
    public void send(OutboundMessage message) {
        CorrelationData correlationData = new CorrelationData(message.messageId());

        try {
            rabbitTemplate.send(message.exchange(), message.routingKey(), toAmqpMessage(message), correlationData);
        } catch (RuntimeException e) {
            throw new UsersEventPublishException("Error publishing the message " + message.messageId() + ": " + e.getMessage(), e);
        }

        CorrelationData.Confirm confirm = waitForConfirm(message, correlationData);

        ReturnedMessage returned = correlationData.getReturned();
        if (returned != null) {
            throw new UsersEventPublishException("Unroutable message (exchange=%s, routingKey=%s): %d %s".formatted(
                    message.exchange(), message.routingKey(), returned.getReplyCode(), returned.getReplyText()));
        }
        if (confirm == null || !confirm.ack()) {
            throw new UsersEventPublishException("RabbitMQ refused the message (nack): "
                    + (confirm == null ? "no detail" : confirm.reason()));
        }
    }

    private CorrelationData.Confirm waitForConfirm(OutboundMessage message, CorrelationData correlationData) {
        try {
            return correlationData.getFuture().get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new UsersEventPublishException("RabbitMQ did not confirm the message " + message.messageId()
                    + " within " + confirmTimeout, e);
        } catch (ExecutionException e) {
            throw new UsersEventPublishException("Error waiting for the confirm of the message " + message.messageId(),
                    e.getCause() != null ? e.getCause() : e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UsersEventPublishException("Interrupted waiting for the confirm of the message " + message.messageId(), e);
        }
    }

    static Message toAmqpMessage(OutboundMessage message) {
        MessageBuilderSupport<Message> builder = MessageBuilder
                .withBody(message.body())
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setMessageId(message.messageId());

        message.headers().forEach(builder::setHeader);

        return builder.build();
    }
}
