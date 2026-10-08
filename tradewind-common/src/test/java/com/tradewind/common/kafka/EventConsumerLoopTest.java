package com.tradewind.common.kafka;

import com.tradewind.common.event.EventEnvelope;
import com.tradewind.common.event.EventJson;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Regression test (found under load in Phase 3): a batch slower than max.poll.interval.ms gets the consumer removed
 * from its group, so the following commit fails. The loop used to exit for good, silently stopping order-status
 * updates while the service still looked healthy. It must recreate the consumer and carry on.
 */
@Testcontainers
class EventConsumerLoopTest {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

    @Test
    void aConsumerRemovedFromItsGroupKeepsConsumingNewRecords() throws Exception {
        TopicSpec topic = new TopicSpec("loop.test", 1);
        Set<UUID> before = produce(topic, 5);
        Set<UUID> processed = ConcurrentHashMap.newKeySet();
        AtomicBoolean stalled = new AtomicBoolean();
        try (EventConsumerLoop loop = new EventConsumerLoop("test-loop", KAFKA.getBootstrapServers(), "test-group",
                List.of(topic), (event, record) -> {
                    if (stalled.compareAndSet(false, true)) {
                        sleep(Duration.ofSeconds(10));      // far longer than max.poll.interval.ms: removed from the group
                    }
                    processed.add(event.eventId());         // handlers dedupe by event id, as the services do
                },
                Map.of(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 1_000, ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 5))) {
            loop.start();
            await().atMost(Duration.ofSeconds(60)).until(() -> processed.containsAll(before));
            // The production failure: after the eviction the loop thread was gone and nothing new was ever applied.
            Set<UUID> after = produce(topic, 5);
            await().atMost(Duration.ofSeconds(60)).until(() -> processed.containsAll(after));
            assertThat(loop.isRunning()).isTrue();
            System.out.println("consumer restarts after the eviction: " + loop.restarts());
        }
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Effects are buffered per transaction and kept only on commit, like a database transaction. */
    static final class FakeTransactions implements EventConsumerLoop.BatchTransaction {
        final Set<UUID> committed = ConcurrentHashMap.newKeySet();
        final ThreadLocal<Set<UUID>> current = new ThreadLocal<>();

        @Override
        public void inTransaction(Runnable work) {
            Set<UUID> pending = new java.util.HashSet<>();
            current.set(pending);
            try {
                work.run();
                committed.addAll(pending);                  // commit
            } finally {
                current.remove();                           // rollback on exception: pending is dropped
            }
        }

        void apply(UUID id) {
            Set<UUID> pending = current.get();
            if (pending != null) {
                pending.add(id);
            } else {
                committed.add(id);                          // record-by-record path: each handler call commits itself
            }
        }
    }

    private static Set<UUID> produce(TopicSpec topic, int count) throws Exception {
        KafkaTopics.ensure(KAFKA.getBootstrapServers(), List.of(topic), Duration.ofSeconds(30));
        Set<UUID> ids = ConcurrentHashMap.newKeySet();
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(KafkaDefaults.producer(KAFKA.getBootstrapServers()))) {
            for (int i = 0; i < count; i++) {
                EventEnvelope<Map<String, Integer>> event = EventEnvelope.of("Test", 1, "k", Map.of("n", i), Clock.systemUTC());
                ids.add(event.eventId());
                producer.send(new ProducerRecord<>(topic.name(), "k", EventJson.write(event))).get();
            }
        }
        return ids;
    }

    @Test
    void batchModeAppliesAWholePollInOneTransaction() throws Exception {
        TopicSpec topic = new TopicSpec("loop.batch", 1);
        Set<UUID> sent = produce(topic, 200);
        FakeTransactions transactions = new FakeTransactions();
        try (EventConsumerLoop loop = new EventConsumerLoop("batch-loop", KAFKA.getBootstrapServers(), "batch-group",
                List.of(topic), (event, record) -> transactions.apply(event.eventId())).withBatchTransactions(transactions)) {
            loop.start();
            await().atMost(Duration.ofSeconds(60)).until(() -> transactions.committed.containsAll(sent));
            assertThat(loop.batches()).isPositive().isLessThan(200);
        }
        assertThat(transactions.committed).isEqualTo(sent);
    }

    @Test
    void aFailingRecordRollsBackItsBatchWhichIsThenAppliedRecordByRecord() throws Exception {
        TopicSpec topic = new TopicSpec("loop.batch.fail", 1);
        Set<UUID> sent = produce(topic, 50);
        UUID poison = sent.iterator().next();
        java.util.concurrent.atomic.AtomicInteger poisonAttempts = new java.util.concurrent.atomic.AtomicInteger();
        FakeTransactions transactions = new FakeTransactions();
        try (EventConsumerLoop loop = new EventConsumerLoop("batch-fail-loop", KAFKA.getBootstrapServers(), "batch-fail-group",
                List.of(topic), (event, record) -> {
                    if (event.eventId().equals(poison) && poisonAttempts.incrementAndGet() <= 3) {
                        throw new IllegalStateException("transient failure");      // e.g. a lock conflict
                    }
                    transactions.apply(event.eventId());
                }).withBatchTransactions(transactions)) {
            loop.start();
            await().atMost(Duration.ofSeconds(60)).until(() -> transactions.committed.containsAll(sent));
        }
        // Nothing from a rolled-back batch leaked, nothing was lost, the poison record was retried until it worked.
        assertThat(transactions.committed).isEqualTo(sent);
        assertThat(poisonAttempts.get()).isGreaterThan(3);
    }
}
