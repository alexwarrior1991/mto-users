package com.alejandro.mtousers.messaging;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.server.context.WebServerGracefulShutdownLifecycle;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The queue between the request thread and the broker, and the one thread that drains it.
 *
 * <p>The HTTP response must not wait for a publisher confirm, and a broker that is down must not
 * make an administrative action fail or hang: the action already happened in Keycloak. So the
 * request thread only offers the ready message to a bounded queue and returns, and this thread
 * sends it, waits for the ack, and retries a few times with growing waits when the broker does not
 * answer ({@code app.rabbitmq.publisher.retry-delays}). After the last attempt the event is lost,
 * and lost means a WARN line and {@code users_events_lost_total{reason="send-failed"}}, never
 * silence. A full queue loses the newest event the same way ({@code reason="queue-full"}): a
 * bounded queue is what keeps a long outage from eating the heap.</p>
 *
 * <p>Retries are per message and in order: while one message is being retried, the ones behind it
 * wait. That is deliberate: a broker that refuses one message refuses the next, and keeping the
 * order of the events of one user is worth more than draining faster.</p>
 *
 * <p>On shutdown it stops accepting, gives the queue {@code drain-timeout} to empty, and then
 * interrupts the thread: what is still waiting is lost and counted. It runs in a lifecycle phase
 * below the web server's, so it is accepting before the first request can arrive and it stops after
 * the graceful shutdown has let the in-flight requests finish: their events still get out.</p>
 */
public class UsersEventDispatcher implements SmartLifecycle {

    public static final String LOST_METRIC = "users_events_lost_total";
    public static final String PUBLISHED_METRIC = "users_events_published_total";
    public static final String QUEUE_SIZE_METRIC = "users_events_queue_size";

    static final String REASON_QUEUE_FULL = "queue-full";
    static final String REASON_SEND_FAILED = "send-failed";
    static final String REASON_SHUTDOWN = "shutdown";

    /** Below Boot's graceful shutdown ({@code MAX - 1024}) and its web server start/stop ({@code MAX - 2048}). */
    static final int PHASE = WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE - 2048;

    private static final Logger log = LoggerFactory.getLogger(UsersEventDispatcher.class);

    private final BlockingQueue<OutboundMessage> queue;
    private final Consumer<OutboundMessage> sender;
    private final List<Duration> retryDelays;
    private final Duration drainTimeout;
    private final Sleeper sleeper;
    private final Counter published;
    private final MeterRegistry meterRegistry;

    private volatile boolean running;
    private volatile boolean accepting;
    private Thread worker;

    public UsersEventDispatcher(Consumer<OutboundMessage> sender, int queueCapacity, List<Duration> retryDelays,
                                Duration drainTimeout, MeterRegistry meterRegistry) {
        this(sender, queueCapacity, retryDelays, drainTimeout, meterRegistry, Sleeper.real());
    }

    UsersEventDispatcher(Consumer<OutboundMessage> sender, int queueCapacity, List<Duration> retryDelays,
                         Duration drainTimeout, MeterRegistry meterRegistry, Sleeper sleeper) {
        this.queue = new LinkedBlockingQueue<>(queueCapacity);
        this.sender = sender;
        this.retryDelays = List.copyOf(retryDelays);
        this.drainTimeout = drainTimeout;
        this.sleeper = sleeper;
        this.meterRegistry = meterRegistry;
        this.published = Counter.builder(PUBLISHED_METRIC)
                .description("Users events confirmed by the broker")
                .register(meterRegistry);
        meterRegistry.gauge(QUEUE_SIZE_METRIC, queue, BlockingQueue::size);
    }

    /** Never blocks and never throws: the request thread is done with the event here, delivered or lost. */
    public void dispatch(OutboundMessage message) {
        if (!accepting) {
            lost(message, REASON_SHUTDOWN, "the dispatcher is stopped");
            return;
        }
        if (!queue.offer(message)) {
            lost(message, REASON_QUEUE_FULL, "the queue is full (" + queue.size() + " waiting)");
        }
    }

    /** Sends one message, retrying with the configured waits; package-private so the test can drive it without the thread. */
    void deliver(OutboundMessage message) {
        int attempt = 0;
        while (true) {
            try {
                sender.accept(message);
                published.increment();
                return;
            } catch (UsersEventPublishException | IllegalStateException e) {
                if (attempt >= retryDelays.size()) {
                    lost(message, REASON_SEND_FAILED, "after " + (attempt + 1) + " attempts: " + e.getMessage());
                    return;
                }
                Duration delay = retryDelays.get(attempt++);
                log.warn("Users event not confirmed by the broker, retry {} in {}: messageId={} eventType={} cause={}",
                        attempt, delay, message.messageId(), message.eventType(), e.getMessage());
                if (!sleeper.sleep(delay)) {
                    lost(message, REASON_SHUTDOWN, "interrupted while waiting to retry");
                    return;
                }
            } catch (RuntimeException e) {
                // Not the broker: a bug. Retrying would repeat it, and letting it out would kill the
                // thread and lose every event after this one in silence. This one is lost, loudly.
                log.error("Unexpected failure publishing a users event: messageId={} eventType={}",
                        message.messageId(), message.eventType(), e);
                lost(message, REASON_SEND_FAILED, "unexpected failure: " + e);
                return;
            }
        }
    }

    private void lost(OutboundMessage message, String reason, String why) {
        log.warn("Users event LOST ({}): messageId={} eventType={} routingKey={}. {}. Keycloak's own admin event remains.",
                reason, message.messageId(), message.eventType(), message.routingKey(), why);
        Counter.builder(LOST_METRIC)
                .description("Users events that never reached the broker")
                .tag("reason", reason)
                .register(meterRegistry)
                .increment();
    }

    int pending() {
        return queue.size();
    }

    // --- lifecycle --------------------------------------------------------------------------------

    @Override
    public void start() {
        if (running) {
            return;
        }
        running = true;
        accepting = true;
        worker = Thread.ofVirtual().name("users-events-dispatch").unstarted(this::drainLoop);
        worker.start();
    }

    @Override
    public void stop() {
        accepting = false;
        if (!running) {
            return;
        }
        running = false;
        try {
            if (worker != null) {
                worker.join(drainTimeout.toMillis());
                if (worker.isAlive()) {
                    log.warn("Users events dispatcher stopped with {} events still waiting", queue.size());
                    worker.interrupt();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        OutboundMessage left;
        while ((left = queue.poll()) != null) {
            lost(left, REASON_SHUTDOWN, "the application is stopping");
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    private void drainLoop() {
        try {
            while (running || !queue.isEmpty()) {
                OutboundMessage message = queue.poll(200, TimeUnit.MILLISECONDS);
                if (message != null) {
                    deliver(message);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Waiting, made replaceable so the tests do not sleep. Returns false when interrupted. */
    interface Sleeper {

        boolean sleep(Duration duration);

        static Sleeper real() {
            return duration -> {
                try {
                    Thread.sleep(duration.toMillis());
                    return true;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            };
        }
    }
}
