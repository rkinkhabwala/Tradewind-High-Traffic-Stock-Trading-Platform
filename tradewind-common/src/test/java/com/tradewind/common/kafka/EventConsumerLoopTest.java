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
    void aConsumerRemovedFromItsGroupRejoinsAndProcessesEverythingExactlyOnceLogically() throws Exception {
        TopicSpec topic = new TopicSpec("loop.test", 1);
        KafkaTopics.ensure(KAFKA.getBootstrapServers(), List.of(topic), Duration.ofSeconds(30));
        Set<UUID> sent = ConcurrentHashMap.newKeySet();
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(KafkaDefaults.producer(KAFKA.getBootstrapServers()))) {
            for (int i = 0; i < 5; i++) {
                EventEnvelope<Map<String, Integer>> event = EventEnvelope.of("Test", 1, "k", Map.of("n", i), Clock.systemUTC());
                sent.add(event.eventId());
                producer.send(new ProducerRecord<>(topic.name(), "k", EventJson.write(event))).get();
            }
        }

        Set<UUID> processed = ConcurrentHashMap.newKeySet();
        AtomicBoolean stalled = new AtomicBoolean();
        try (EventConsumerLoop loop = new EventConsumerLoop("test-loop", KAFKA.getBootstrapServers(), "test-group",
                List.of(topic), (event, record) -> {
                    if (stalled.compareAndSet(false, true)) {
                        sleep(Duration.ofSeconds(5));       // longer than max.poll.interval.ms: removed from the group
                    }
                    processed.add(event.eventId());         // handlers dedupe by event id, as the services do
                },
                Map.of(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 2_000, ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 5))) {
            loop.start();
            await().atMost(Duration.ofSeconds(60)).until(() -> processed.containsAll(sent));
            assertThat(loop.restarts()).isPositive();
            assertThat(loop.isRunning()).isTrue();
        }
        assertThat(processed).isEqualTo(sent);
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
