package com.alejandro.mtousers.configuration.messaging;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * What this service publishes and how it copes with a broker that does not answer.
 *
 * @param enabled  with {@code false} nothing is declared and nothing is published: the tests, and
 *                 an environment without a broker
 * @param exchange the topic exchange of this service; the consumers bind their queues to it
 * @param publisher the queue between the request thread and the sender, and the retries
 */
@Validated
@ConfigurationProperties(prefix = "app.rabbitmq")
public record UsersMessagingProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("mto.users.exchange") @NotBlank String exchange,
        @DefaultValue @NotNull Publisher publisher
) {

    /**
     * @param queueCapacity  events waiting to be sent; beyond it, an event is lost and counted. A
     *                       bounded queue is what keeps a broker outage from eating the heap
     * @param confirmTimeout how long to wait for the broker's confirm of one message
     * @param retryDelays    the waits between attempts; the number of retries is their count
     * @param drainTimeout   how long the shutdown waits for the queue to empty
     */
    public record Publisher(
            @DefaultValue("1000") @Min(1) int queueCapacity,
            @DefaultValue("10s") @NotNull Duration confirmTimeout,
            @DefaultValue({"1s", "5s", "30s"}) @NotEmpty List<Duration> retryDelays,
            @DefaultValue("10s") @NotNull Duration drainTimeout
    ) {
    }
}
