package com.tradewind.common.userevent;

import com.tradewind.common.event.EventJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Best-effort push of user-specific messages (order status, portfolio changes) to Redis pub/sub channel
 * {@code user:{accountId}}, which stream-service relays to that account's open sockets (spec 6.2).
 * <p>
 * Never inside a transaction: inside one, the message is serialized now and sent only after commit, so a rolled-back
 * change is never announced; outside one, the change has already committed and the message goes out at once.
 * Sends run on one background thread (keeping per-account order) behind a bounded queue, so a slow or down Redis
 * never delays the caller; overflow and failures are dropped and counted. REST stays the source of truth: clients
 * refetch on reconnect.
 */
public final class UserEventPublisher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(UserEventPublisher.class);
    public static final int DEFAULT_QUEUE_CAPACITY = 10_000;

    /** Sends one message to one channel; implemented with Redis PUBLISH by the services. */
    @FunctionalInterface
    public interface Transport {
        void publish(String channel, String message);
    }

    private final Transport transport;
    private final ThreadPoolExecutor sender;
    private final AtomicLong published = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();

    public UserEventPublisher(Transport transport) {
        this(transport, DEFAULT_QUEUE_CAPACITY);
    }

    public UserEventPublisher(Transport transport, int queueCapacity) {
        this.transport = transport;
        this.sender = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queueCapacity),
                Thread.ofPlatform().name("user-events").daemon().factory(),
                (task, executor) -> dropped.incrementAndGet());
    }

    public static String channel(UUID accountId) {
        return "user:" + accountId;
    }

    /** Publishes {@code message} (serialized with the shared event JSON settings) once the current transaction commits. */
    public void publishAfterCommit(UUID accountId, Object message) {
        String channel = channel(accountId);
        String json = EventJson.write(message);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    enqueue(channel, json);
                }
            });
        } else {
            enqueue(channel, json);
        }
    }

    private void enqueue(String channel, String json) {
        sender.execute(() -> {
            try {
                transport.publish(channel, json);
                published.incrementAndGet();
            } catch (RuntimeException e) {
                failed.incrementAndGet();
                log.debug("Could not publish to {}: {}", channel, e.toString());
            }
        });
    }

    public long published() {
        return published.get();
    }

    public long dropped() {
        return dropped.get();
    }

    public long failed() {
        return failed.get();
    }

    @Override
    public void close() {
        sender.shutdown();
        try {
            sender.awaitTermination(Duration.ofSeconds(2).toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
