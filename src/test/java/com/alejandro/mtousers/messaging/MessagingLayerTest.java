package com.alejandro.mtousers.messaging;

import com.alejandro.mtousers.configuration.messaging.MessageSignatureProperties;
import com.alejandro.mtousers.configuration.messaging.MessagingConfiguration;
import com.alejandro.mtousers.configuration.security.CurrentUserService;
import com.alejandro.mtousers.configuration.security.JwtClaimNames;
import com.alejandro.mtousers.configuration.web.CorrelationIdFilter;
import com.alejandro.mtousers.dto.RequiredAction;
import com.alejandro.mtousers.service.AdminAuditLog.AdminAction;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.server.context.WebServerGracefulShutdownLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Everything between an administrative action and the broker: the names of the contract, the
 * envelope built in the request thread, the signature over the bytes, one send with its confirm,
 * the queue with its retries and its losses, the publisher that ties them, the wiring, and the
 * examples versioned for the consumer. No broker: the template is a double, and the queue's thread
 * is driven with latches instead of sleeps.
 */
class MessagingLayerTest {

    private static final String USER_ID = "2f1c9d1e-0000-4000-8000-000000000001";
    private static final String ACTOR_ID = "6f1b1c8e-0000-4000-8000-000000000002";
    private static final Instant NOW = Instant.parse("2026-09-29T09:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        MDC.remove(CorrelationIdFilter.MDC_KEY);
    }

    // --- the names --------------------------------------------------------------------------------

    @Nested
    class Names {

        @Test
        void everyAdminActionHasItsEntityAndEvent() {
            for (AdminAction action : AdminAction.values()) {
                assertNotNull(UsersEventNames.of(action), action.name());
            }
        }

        /** The consumer derives the activity type from the routing key: these strings are the contract. */
        @Test
        void routingKeysAndEventTypesAreTheOnesTheConsumerExpects() {
            Map<AdminAction, String> expected = new LinkedHashMap<>();
            expected.put(AdminAction.USER_CREATED, "mto.users.user.created");
            expected.put(AdminAction.USER_UPDATED, "mto.users.user.updated");
            expected.put(AdminAction.USER_ENABLED, "mto.users.user.enabled");
            expected.put(AdminAction.USER_DISABLED, "mto.users.user.disabled");
            expected.put(AdminAction.USER_DELETED, "mto.users.user.deleted");
            expected.put(AdminAction.PASSWORD_RESET, "mto.users.user.password-reset");
            expected.put(AdminAction.ACTIONS_EMAIL_SENT, "mto.users.user.actions-email-sent");
            expected.put(AdminAction.CLIENT_ROLES_ADDED, "mto.users.client-roles.added");
            expected.put(AdminAction.CLIENT_ROLES_REMOVED, "mto.users.client-roles.removed");
            expected.put(AdminAction.PROFILE_ASSIGNED, "mto.users.profile.assigned");
            expected.put(AdminAction.PROFILE_REMOVED, "mto.users.profile.removed");
            expected.put(AdminAction.SESSION_REVOKED, "mto.users.session.revoked");
            expected.put(AdminAction.ALL_SESSIONS_REVOKED, "mto.users.session.all-revoked");
            expected.put(AdminAction.OFFLINE_SESSION_REVOKED, "mto.users.offline-session.revoked");
            expected.put(AdminAction.ALL_OFFLINE_SESSIONS_REVOKED, "mto.users.offline-session.all-revoked");
            expected.put(AdminAction.CREDENTIAL_DELETED, "mto.users.credential.deleted");

            assertEquals(AdminAction.values().length, expected.size());
            expected.forEach((action, routingKey) ->
                    assertEquals(routingKey, UsersEventNames.routingKey(UsersEventNames.of(action)), action.name()));

            assertEquals("USERS_USER_CREATED", UsersEventNames.eventType(UsersEventNames.of(AdminAction.USER_CREATED)));
            assertEquals("USERS_OFFLINE_SESSION_ALL_REVOKED",
                    UsersEventNames.eventType(UsersEventNames.of(AdminAction.ALL_OFFLINE_SESSIONS_REVOKED)));
            assertEquals("mto.users.exchange", UsersEventNames.EXCHANGE);
            assertEquals("mto.users.#", UsersEventNames.ROUTING_PATTERN);
        }
    }

    // --- the envelope -----------------------------------------------------------------------------

    @Nested
    class Envelope {

        private final UsersEventEnvelopeFactory factory = new UsersEventEnvelopeFactory(
                new CurrentUserService(), objectMapper, "mto-users", Clock.fixed(NOW, ZoneOffset.UTC));

        @Test
        void carriesTheEventTheTargetAndWhatTheServiceTold() {
            AsynchronousMessage<DomainEvent> message = factory.create(AdminAction.CLIENT_ROLES_ADDED, USER_ID, "ana.uno",
                    Map.of("client", "mto-stock-api"));

            assertEquals("user-" + USER_ID, message.referenceId());
            assertEquals("mto-users", message.origin());
            assertEquals(NOW, message.creationDate());
            assertEquals("USERS_CLIENT_ROLES_ADDED", message.eventType());
            assertEquals("client-roles", message.data().entityName());
            assertEquals(USER_ID, message.data().entityId(), "The user is the aggregate of everything this service does");
            assertEquals("added", message.data().eventName());
            assertEquals(List.of("targetUserId", "targetUsername", "client"), new ArrayList<>(message.data().values().keySet()),
                    "Who the event is about comes first, then what the service told");
            assertEquals("ana.uno", message.data().values().get("targetUsername"));
            assertTrue(message.messageHash().matches("[0-9a-f]{64}"));
            assertNotNull(message.operationId());
        }

        @Test
        void theActorIsThePersonOfTheTokenWithHerSubAndUsername() {
            authenticate("usuarios.responsable");

            MessageActor actor = factory.create(AdminAction.USER_UPDATED, USER_ID, null, Map.of()).actor();

            assertEquals(new MessageActor(ACTOR_ID, "usuarios.responsable", MessageActorKind.PERSON), actor);
        }

        @Test
        void aServiceAccountIsClassifiedAsServiceByKeycloaksPrefix() {
            authenticate("service-account-mto-backoffice-svc");

            assertEquals(MessageActorKind.SERVICE, factory.create(AdminAction.USER_UPDATED, USER_ID, null, Map.of()).actor().kind());
        }

        @Test
        void withoutAnAuthenticatedUserTheActorIsSystemSaidExplicitly() {
            assertEquals(MessageActor.system(), factory.create(AdminAction.USER_UPDATED, USER_ID, null, Map.of()).actor());
        }

        @Test
        void theCorrelationIdIsTheOneOfTheRequestOrNothing() {
            assertNull(factory.create(AdminAction.USER_UPDATED, USER_ID, null, Map.of()).correlationId());

            MDC.put(CorrelationIdFilter.MDC_KEY, "8c3b8c1a-1111-4222-8333-444444444444");

            assertEquals("8c3b8c1a-1111-4222-8333-444444444444",
                    factory.create(AdminAction.USER_UPDATED, USER_ID, null, Map.of()).correlationId());
        }

        /** The last door: a value named like a credential does not leave, whatever the service meant. */
        @Test
        void aValueNamedLikeACredentialIsRefused() {
            assertThrows(IllegalArgumentException.class,
                    () -> factory.create(AdminAction.PASSWORD_RESET, USER_ID, null, Map.of("password", "Secreta.123")));
            assertThrows(IllegalArgumentException.class,
                    () -> factory.create(AdminAction.USER_CREATED, USER_ID, null, Map.of("temporaryPasswordValue", "x")));
            assertThrows(IllegalArgumentException.class,
                    () -> factory.create(AdminAction.USER_CREATED, USER_ID, null, Map.of("accessToken", "x")));
        }

        @Test
        void theHashCoversTheSevenOriginalKeysOnlyLikeEveryProducerOfTheDomain() {
            UUID operationId = UUID.fromString("7c2e6a10-1b2c-4d3e-8f90-0a1b2c3d4e5f");

            AsynchronousMessage<DomainEvent> asSystem = factory.create(operationId, AdminAction.USER_DELETED, USER_ID, "ana.baja", Map.of());
            authenticate("usuarios.responsable");
            MDC.put(CorrelationIdFilter.MDC_KEY, "8c3b8c1a-1111-4222-8333-444444444444");
            AsynchronousMessage<DomainEvent> asPerson = factory.create(operationId, AdminAction.USER_DELETED, USER_ID, "ana.baja", Map.of());

            assertEquals(asSystem.messageHash(), asPerson.messageHash(), "Neither the actor nor the correlation change it");
        }
    }

    // --- the signature ----------------------------------------------------------------------------

    @Nested
    class Signature {

        @Test
        void withASecretItIsAnHmacThatOnlyTheSecretCanReproduce() {
            MessagePayloadSignature signature = new MessagePayloadSignature(new MessageSignatureProperties("shared-secret"));
            byte[] body = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);

            String signed = signature.sign(body);

            assertEquals("HMAC-SHA256", signature.algorithm());
            assertTrue(signature.verify(body, signed));
            assertFalse(signature.verify("{\"a\":2}".getBytes(StandardCharsets.UTF_8), signed));
            assertFalse(new MessagePayloadSignature(new MessageSignatureProperties("other")).verify(body, signed));
        }

        @Test
        void withoutASecretItIsAPlainDigestThatDetectsCorruptionOnly() {
            MessagePayloadSignature signature = new MessagePayloadSignature(new MessageSignatureProperties(""));
            byte[] body = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);

            assertEquals("SHA-256", signature.algorithm());
            assertTrue(signature.verify(body, signature.sign(body)));
            assertFalse(signature.verify(body, "0000"));
        }
    }

    // --- one send, with its confirm ---------------------------------------------------------------

    @Nested
    class Sender {

        private final RabbitTemplate template = mock(RabbitTemplate.class);
        private final UsersEventSender sender = new UsersEventSender(template, Duration.ofMillis(200));
        private final OutboundMessage message = outbound("m-1");

        @Test
        void aMessageCountsAsSentOnlyWithTheBrokersAck() {
            confirmWith(true, null);

            sender.send(message);

            var captor = org.mockito.ArgumentCaptor.forClass(Message.class);
            verify(template).send(eq(UsersEventNames.EXCHANGE), eq("mto.users.user.created"), captor.capture(), any(CorrelationData.class));
            MessageProperties properties = captor.getValue().getMessageProperties();
            assertEquals("m-1", properties.getMessageId());
            assertEquals(MessageDeliveryMode.PERSISTENT, properties.getDeliveryMode());
            assertEquals(MessageProperties.CONTENT_TYPE_JSON, properties.getContentType());
            assertEquals("USERS_USER_CREATED", properties.getHeader("eventType"));
            assertEquals("firma", properties.getHeader(MessagePayloadSignature.HEADER_SIGNATURE));
            assertEquals("{}", new String(captor.getValue().getBody(), StandardCharsets.UTF_8));
        }

        @Test
        void aNackIsAFailure() {
            confirmWith(false, "queue overflow");

            UsersEventPublishException failure = assertThrows(UsersEventPublishException.class, () -> sender.send(message));
            assertTrue(failure.getMessage().contains("nack"));
        }

        /** With mandatory=true an unroutable message comes back before the ack: silence would be losing it. */
        @Test
        void anUnroutableMessageIsAFailureEvenIfTheBrokerAcks() {
            doAnswer(invocation -> {
                CorrelationData correlationData = invocation.getArgument(3);
                correlationData.setReturned(new ReturnedMessage(new Message(new byte[0]), 312, "NO_ROUTE", UsersEventNames.EXCHANGE, "k"));
                correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));
                return null;
            }).when(template).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

            UsersEventPublishException failure = assertThrows(UsersEventPublishException.class, () -> sender.send(message));
            assertTrue(failure.getMessage().contains("Unroutable"));
            assertTrue(failure.getMessage().contains("NO_ROUTE"));
        }

        @Test
        void aConfirmThatNeverArrivesIsAFailureAfterTheTimeout() {
            doAnswer(invocation -> null).when(template).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

            UsersEventPublishException failure = assertThrows(UsersEventPublishException.class, () -> sender.send(message));
            assertTrue(failure.getMessage().contains("did not confirm"));
        }

        @Test
        void aConnectionFailureIsAFailureAndNotAnEscapedException() {
            doThrow(new AmqpConnectException(new java.net.ConnectException("refused")))
                    .when(template).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

            assertThrows(UsersEventPublishException.class, () -> sender.send(message));
        }

        private void confirmWith(boolean ack, String reason) {
            doAnswer(invocation -> {
                CorrelationData correlationData = invocation.getArgument(3);
                correlationData.getFuture().complete(new CorrelationData.Confirm(ack, reason));
                return null;
            }).when(template).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        }
    }

    // --- the queue, its retries and its losses ----------------------------------------------------

    @Nested
    class Dispatcher {

        private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        private final List<Duration> slept = new ArrayList<>();
        private final UsersEventDispatcher.Sleeper fakeSleeper = duration -> {
            slept.add(duration);
            return true;
        };

        private UsersEventDispatcher dispatcher(java.util.function.Consumer<OutboundMessage> sender, int capacity) {
            return new UsersEventDispatcher(sender, capacity, List.of(Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(30)),
                    Duration.ofSeconds(2), meterRegistry, fakeSleeper);
        }

        @Test
        void aConfirmedMessageIsCountedAsPublished() {
            List<OutboundMessage> sent = new ArrayList<>();

            dispatcher(sent::add, 10).deliver(outbound("m-1"));

            assertEquals(1, sent.size());
            assertEquals(1.0, meterRegistry.get(UsersEventDispatcher.PUBLISHED_METRIC).counter().count());
            assertEquals(List.of(), slept);
        }

        @Test
        void aBrokerThatFailsIsRetriedWithTheConfiguredWaitsAndInOrder() {
            AtomicInteger attempts = new AtomicInteger();
            java.util.function.Consumer<OutboundMessage> flaky = message -> {
                if (attempts.incrementAndGet() < 3) {
                    throw new UsersEventPublishException("nack");
                }
            };

            dispatcher(flaky, 10).deliver(outbound("m-1"));

            assertEquals(3, attempts.get());
            assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(5)), slept, "One wait per failed attempt, growing");
            assertEquals(1.0, meterRegistry.get(UsersEventDispatcher.PUBLISHED_METRIC).counter().count());
            assertEquals(0, lost("send-failed"));
        }

        /** Lost is loud: a WARN line and a counter with the reason. Never silence. */
        @Test
        void afterTheLastRetryTheMessageIsLostAndCounted() {
            AtomicInteger attempts = new AtomicInteger();
            java.util.function.Consumer<OutboundMessage> broken = message -> {
                attempts.incrementAndGet();
                throw new UsersEventPublishException("nack");
            };

            dispatcher(broken, 10).deliver(outbound("m-1"));

            assertEquals(4, attempts.get(), "One attempt plus three retries");
            assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(30)), slept);
            assertEquals(1, lost("send-failed"));
            assertEquals(0.0, meterRegistry.get(UsersEventDispatcher.PUBLISHED_METRIC).counter().count());
        }

        /** A sender blocked in the broker makes the fill deterministic: the queue holds what the capacity says. */
        @Test
        void aFullQueueLosesTheNewestMessageAndCountsIt() {
            CountDownLatch release = new CountDownLatch(1);
            UsersEventDispatcher blocked = dispatcher(message -> {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, 2);
            blocked.start();
            try {
                blocked.dispatch(outbound("m-1")); // taken by the thread and blocked in the sender
                waitUntil(() -> blocked.pending() == 0);
                blocked.dispatch(outbound("m-2"));
                blocked.dispatch(outbound("m-3"));
                blocked.dispatch(outbound("m-4")); // capacity 2: this one does not fit

                assertEquals(1, lost("queue-full"));
                assertEquals(2, blocked.pending());
            } finally {
                release.countDown();
                blocked.stop();
            }
        }

        /** A bug in one message is not the broker: no retry, that message is lost loudly, and the thread goes on. */
        @Test
        void anUnexpectedFailureLosesThatMessageOnlyAndTheThreadGoesOn() throws InterruptedException {
            List<String> sent = new java.util.concurrent.CopyOnWriteArrayList<>();
            CountDownLatch second = new CountDownLatch(1);
            UsersEventDispatcher dispatcher = dispatcher(message -> {
                if (message.messageId().equals("m-1")) {
                    throw new NullPointerException("a bug");
                }
                sent.add(message.messageId());
                second.countDown();
            }, 10);

            dispatcher.start();
            dispatcher.dispatch(outbound("m-1"));
            dispatcher.dispatch(outbound("m-2"));
            boolean secondDelivered = second.await(5, TimeUnit.SECONDS);
            dispatcher.stop(); // joins the thread: the counters of both deliveries are settled

            assertTrue(secondDelivered);
            assertEquals(List.of("m-2"), sent);
            assertEquals(List.of(), slept, "No retry for a bug");
            assertEquals(1, lost("send-failed"));
            assertEquals(1.0, meterRegistry.get(UsersEventDispatcher.PUBLISHED_METRIC).counter().count());
        }

        /** Started before the web server accepts and stopped after its graceful shutdown: the last requests' events get out. */
        @Test
        void itRunsInAPhaseBelowTheWebServers() {
            UsersEventDispatcher dispatcher = dispatcher(message -> { }, 10);

            assertTrue(dispatcher.getPhase() < WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE - 1024,
                    "Below the graceful shutdown (MAX - 1024) and below the server start/stop (MAX - 2048)");
            assertTrue(dispatcher.isAutoStartup());
        }

        @Test
        void theThreadDrainsWhatWasQueuedAndTheStopWaitsForIt() throws InterruptedException {
            List<String> sent = new java.util.concurrent.CopyOnWriteArrayList<>();
            CountDownLatch three = new CountDownLatch(3);
            UsersEventDispatcher dispatcher = dispatcher(message -> {
                sent.add(message.messageId());
                three.countDown();
            }, 10);

            dispatcher.start();
            dispatcher.dispatch(outbound("m-1"));
            dispatcher.dispatch(outbound("m-2"));
            dispatcher.dispatch(outbound("m-3"));

            assertTrue(three.await(5, TimeUnit.SECONDS));
            dispatcher.stop();

            assertEquals(List.of("m-1", "m-2", "m-3"), sent, "In order, one thread");
            assertFalse(dispatcher.isRunning());
            assertEquals(1, lost("shutdown", dispatcher, outbound("after-stop")), "After the stop nothing is accepted, and it is counted");
        }

        private int lost(String reason) {
            return (int) meterRegistry.find(UsersEventDispatcher.LOST_METRIC).tag("reason", reason).counters().stream()
                    .mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
        }

        private int lost(String reason, UsersEventDispatcher dispatcher, OutboundMessage message) {
            dispatcher.dispatch(message);
            return lost(reason);
        }

        private void waitUntil(java.util.function.BooleanSupplier condition) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!condition.getAsBoolean()) {
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("Condition not met in time");
                }
                Thread.onSpinWait();
            }
        }
    }

    // --- the publisher ----------------------------------------------------------------------------

    @Nested
    class Publisher {

        private final MessagePayloadSignature signature = new MessagePayloadSignature(new MessageSignatureProperties("shared-secret"));
        private final UsersEventEnvelopeFactory factory = new UsersEventEnvelopeFactory(
                new CurrentUserService(), objectMapper, "mto-users", Clock.fixed(NOW, ZoneOffset.UTC));

        @Test
        void theOutboundMessageCarriesTheSignedBytesAndTheRoutingOfTheEvent() {
            RabbitUsersEventPublisher publisher = new RabbitUsersEventPublisher(factory, objectMapper, signature,
                    UsersEventNames.EXCHANGE, mock(UsersEventDispatcher.class));
            AsynchronousMessage<DomainEvent> envelope = factory.create(AdminAction.PROFILE_ASSIGNED, USER_ID, null,
                    Map.of("profile", "mto-users-viewer"));

            OutboundMessage outbound = publisher.toOutbound(envelope);

            assertEquals(envelope.operationId().toString(), outbound.messageId());
            assertEquals(UsersEventNames.EXCHANGE, outbound.exchange());
            assertEquals("mto.users.profile.assigned", outbound.routingKey());
            assertEquals("USERS_PROFILE_ASSIGNED", outbound.headers().get("eventType"));
            assertEquals("profile", outbound.headers().get("aggregateType"));
            assertEquals(USER_ID, outbound.headers().get("aggregateId"));
            assertEquals("HMAC-SHA256", outbound.headers().get(MessagePayloadSignature.HEADER_SIGNATURE_ALGORITHM));
            assertTrue(signature.verify(outbound.body(), (String) outbound.headers().get(MessagePayloadSignature.HEADER_SIGNATURE)),
                    "The signature is over the exact bytes that travel");
            JsonNode json = objectMapper.readTree(outbound.body());
            assertEquals("mto-users-viewer", json.at("/data/values/profile").asText());
            assertEquals("SYSTEM", json.at("/actor/kind").asText());
        }

        /** The actor and the correlation are read in the caller's thread: the dispatcher's thread has neither. */
        @Test
        void theEnvelopeIsBuiltInTheCallersThreadAndSentFromAnother() throws InterruptedException {
            AtomicReference<String> senderThread = new AtomicReference<>();
            AtomicReference<byte[]> sentBody = new AtomicReference<>();
            CountDownLatch sent = new CountDownLatch(1);
            UsersEventDispatcher dispatcher = new UsersEventDispatcher(message -> {
                senderThread.set(Thread.currentThread().getName());
                sentBody.set(message.body());
                sent.countDown();
            }, 10, List.of(Duration.ofSeconds(1)), Duration.ofSeconds(2), new SimpleMeterRegistry());
            RabbitUsersEventPublisher publisher = new RabbitUsersEventPublisher(factory, objectMapper, signature,
                    UsersEventNames.EXCHANGE, dispatcher);

            dispatcher.start();
            try {
                authenticate("usuarios.responsable");
                MDC.put(CorrelationIdFilter.MDC_KEY, "8c3b8c1a-1111-4222-8333-444444444444");

                publisher.publish(AdminAction.USER_DISABLED, USER_ID, "ana.uno", Map.of());

                assertTrue(sent.await(5, TimeUnit.SECONDS));
                assertEquals("users-events-dispatch", senderThread.get());
                JsonNode json = objectMapper.readTree(sentBody.get());
                assertEquals("usuarios.responsable", json.at("/actor/username").asText());
                assertEquals("8c3b8c1a-1111-4222-8333-444444444444", json.at("/correlationId").asText());
                assertEquals("ana.uno", json.at("/data/values/targetUsername").asText());
            } finally {
                dispatcher.stop();
            }
        }
    }

    // --- the wiring -------------------------------------------------------------------------------

    @Nested
    class Wiring {

        private ApplicationContextRunner runner(boolean publisherConfirms) {
            return new ApplicationContextRunner()
                    .withUserConfiguration(MessagingConfiguration.class, RabbitStubConfiguration.class)
                    .withBean(CurrentUserService.class)
                    .withBean(ObjectMapper.class, ObjectMapper::new)
                    .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                    .withPropertyValues("mto.test.publisher-confirms=" + publisherConfirms);
        }

        @Test
        void withABrokerTheRealPublisherAndTheExchangeAreThere() {
            runner(true).run(context -> {
                assertNotNull(context.getBean(RabbitUsersEventPublisher.class));
                assertEquals(1, context.getBeansOfType(UsersEventPublisher.class).size());
                assertEquals("mto.users.exchange", context.getBean(TopicExchange.class).getName());
                assertTrue(context.getBean(TopicExchange.class).isDurable());
                assertTrue(context.getBean(UsersEventDispatcher.class).isRunning(), "Started with the context");
            });
        }

        /** Without confirms the future never completes and every event would wait for the timeout and be lost: better not to start. */
        @Test
        void withoutPublisherConfirmsItDoesNotStart() {
            runner(false).run(context -> {
                assertNotNull(context.getStartupFailure());
                assertTrue(rootCause(context.getStartupFailure()).getMessage().contains("publisher-confirm-type=correlated"));
            });
        }

        @Test
        void withTheSwitchOffOnlyTheNoOpIsThere() {
            runner(true).withPropertyValues("app.rabbitmq.enabled=false").run(context -> {
                assertNotNull(context.getBean(NoOpUsersEventPublisher.class));
                assertEquals(1, context.getBeansOfType(UsersEventPublisher.class).size());
                assertEquals(0, context.getBeansOfType(TopicExchange.class).size());
                assertEquals(0, context.getBeansOfType(UsersEventDispatcher.class).size());
            });
        }

        private Throwable rootCause(Throwable failure) {
            Throwable cause = failure;
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }
            return cause;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RabbitStubConfiguration {

        @Bean
        RabbitTemplate rabbitTemplate(@org.springframework.beans.factory.annotation.Value("${mto.test.publisher-confirms}") boolean confirms) {
            CachingConnectionFactory connectionFactory = mock(CachingConnectionFactory.class);
            when(connectionFactory.isPublisherConfirms()).thenReturn(confirms);
            RabbitTemplate template = mock(RabbitTemplate.class);
            when(template.getConnectionFactory()).thenReturn(connectionFactory);
            return template;
        }
    }

    // --- the examples for the consumer -----------------------------------------------------------

    @Nested
    class Examples {

        private final Path examples = Path.of("docs", "messaging", "examples");
        private final UsersEventEnvelopeFactory factory = new UsersEventEnvelopeFactory(
                new CurrentUserService(), objectMapper, "mto-users", Clock.fixed(NOW, ZoneOffset.UTC));

        @Test
        void aUserCreatedByAPersonIsPublishedAsTheExample() throws IOException {
            authenticate("usuarios.responsable");
            MDC.put(CorrelationIdFilter.MDC_KEY, "8c3b8c1a-1111-4222-8333-444444444444");

            AsynchronousMessage<DomainEvent> message = factory.create(UUID.fromString("7c2e6a10-1b2c-4d3e-8f90-0a1b2c3d4e5f"),
                    AdminAction.USER_CREATED, USER_ID, "ana.nueva", orderedDetail(
                            "enabled", true, "temporaryCredential", true, "requiredActions", List.of(RequiredAction.UPDATE_PASSWORD)));

            assertSameAsExample(message, "user-created.json");
        }

        @Test
        void aProfileAssignedIsPublishedAsTheExample() throws IOException {
            authenticate("usuarios.responsable");
            MDC.put(CorrelationIdFilter.MDC_KEY, "8c3b8c1a-1111-4222-8333-444444444444");

            AsynchronousMessage<DomainEvent> message = factory.create(UUID.fromString("9d4f7b21-3c5e-4a6f-9b80-1c2d3e4f5a6b"),
                    AdminAction.PROFILE_ASSIGNED, USER_ID, null, orderedDetail("profile", "mto-users-viewer"));

            assertSameAsExample(message, "profile-assigned.json");
        }

        /** With a fixed clock the whole envelope is comparable, hash included: the example is exactly what travels. */
        private void assertSameAsExample(AsynchronousMessage<DomainEvent> message, String fileName) throws IOException {
            Path file = examples.resolve(fileName);
            assertTrue(Files.exists(file), "The example " + fileName + " must be versioned");

            JsonNode produced = objectMapper.readTree(objectMapper.writeValueAsString(message));
            JsonNode example = objectMapper.readTree(Files.readString(file));

            assertEquals(example, produced);
        }

        private Map<String, Object> orderedDetail(Object... keysAndValues) {
            Map<String, Object> detail = new LinkedHashMap<>();
            for (int i = 0; i < keysAndValues.length; i += 2) {
                detail.put((String) keysAndValues[i], keysAndValues[i + 1]);
            }
            return detail;
        }
    }

    // --- helpers ----------------------------------------------------------------------------------

    private static OutboundMessage outbound(String messageId) {
        return new OutboundMessage(messageId, UsersEventNames.EXCHANGE, "mto.users.user.created", "USERS_USER_CREATED",
                "{}".getBytes(StandardCharsets.UTF_8), Map.of("eventType", "USERS_USER_CREATED",
                MessagePayloadSignature.HEADER_SIGNATURE, "firma"));
    }

    private static void authenticate(String username) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(ACTOR_ID)
                .claim(JwtClaimNames.PREFERRED_USERNAME, username)
                .build();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new JwtAuthenticationToken(jwt, AuthorityUtils.createAuthorityList("ROLE_USERS_WRITE"), username));
        SecurityContextHolder.setContext(context);
    }
}
