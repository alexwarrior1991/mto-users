package com.alejandro.mtousers.configuration.messaging;

import com.alejandro.mtousers.configuration.security.CurrentUserService;
import com.alejandro.mtousers.messaging.MessagePayloadSignature;
import com.alejandro.mtousers.messaging.NoOpUsersEventPublisher;
import com.alejandro.mtousers.messaging.RabbitUsersEventPublisher;
import com.alejandro.mtousers.messaging.UsersEventDispatcher;
import com.alejandro.mtousers.messaging.UsersEventPublisher;
import com.alejandro.mtousers.messaging.UsersEventSender;
import com.alejandro.mtousers.messaging.UsersEventEnvelopeFactory;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * The wiring of the events this service publishes towards {@code mto-notification}.
 *
 * <p>Gated on {@code app.rabbitmq.enabled}: with it off, nothing is declared, nothing connects and
 * the {@link NoOpUsersEventPublisher} takes the place of the real one, which is how the tests and
 * an environment without a broker run. With it on, the exchange is declared here (and by the
 * consumer, with the same arguments, so that the order of deployment does not matter) and the
 * publisher refuses to start without publisher confirms: without them the confirm future never
 * completes and every message would wait for the timeout and be counted as lost.</p>
 */
@Configuration
@EnableConfigurationProperties({UsersMessagingProperties.class, MessageSignatureProperties.class})
public class MessagingConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "app.rabbitmq", name = "enabled", havingValue = "false")
    public UsersEventPublisher noOpUsersEventPublisher() {
        return new NoOpUsersEventPublisher();
    }

    @Configuration
    @ConditionalOnProperty(prefix = "app.rabbitmq", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class RabbitEnabledConfiguration {

        /** The producer's contract: a durable topic exchange. The queues are the consumers' to declare. */
        @Bean
        public TopicExchange usersExchange(UsersMessagingProperties properties) {
            return new TopicExchange(properties.exchange(), true, false);
        }

        @Bean
        public MessagePayloadSignature messagePayloadSignature(MessageSignatureProperties properties) {
            return new MessagePayloadSignature(properties);
        }

        @Bean
        public UsersEventEnvelopeFactory usersEventEnvelopeFactory(CurrentUserService currentUser, ObjectMapper objectMapper,
                                                                   @Value("${spring.application.name:mto-users}") String applicationName) {
            return new UsersEventEnvelopeFactory(currentUser, objectMapper, applicationName);
        }

        @Bean
        public UsersEventSender usersEventSender(RabbitTemplate rabbitTemplate, UsersMessagingProperties properties) {
            requirePublisherConfirms(rabbitTemplate.getConnectionFactory());
            return new UsersEventSender(rabbitTemplate, properties.publisher().confirmTimeout());
        }

        @Bean
        public UsersEventDispatcher usersEventDispatcher(UsersEventSender sender, UsersMessagingProperties properties,
                                                         MeterRegistry meterRegistry) {
            UsersMessagingProperties.Publisher publisher = properties.publisher();
            return new UsersEventDispatcher(sender::send, publisher.queueCapacity(), publisher.retryDelays(),
                    publisher.drainTimeout(), meterRegistry);
        }

        @Bean
        public UsersEventPublisher rabbitUsersEventPublisher(UsersEventEnvelopeFactory envelopeFactory, ObjectMapper objectMapper,
                                                             MessagePayloadSignature signature, UsersMessagingProperties properties,
                                                             UsersEventDispatcher dispatcher) {
            return new RabbitUsersEventPublisher(envelopeFactory, objectMapper, signature, properties.exchange(), dispatcher);
        }

        /**
         * Without publisher confirms the future of {@code CorrelationData} never completes and the
         * sender would wait for the timeout on every message. Rather than degrade in silence, it
         * does not start.
         */
        private static void requirePublisherConfirms(ConnectionFactory connectionFactory) {
            if (connectionFactory instanceof CachingConnectionFactory caching && caching.isPublisherConfirms()) {
                return;
            }
            throw new IllegalStateException("""
                    Publishing users events requires publisher confirms, so that an event counts as sent \
                    only when RabbitMQ has accepted it. Set spring.rabbitmq.publisher-confirm-type=correlated \
                    (and publisher-returns=true to detect unroutable messages).""");
        }
    }
}
