package com.tradewind.execution.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.tradewind.common.event.EventEnvelope;
import com.tradewind.common.event.EventJson;
import com.tradewind.common.event.EventTypes;
import com.tradewind.common.kafka.KafkaDefaults;
import com.tradewind.common.kafka.KafkaTopics;
import com.tradewind.common.kafka.Topics;
import com.tradewind.common.order.CancelRequested;
import com.tradewind.common.order.OrderAccepted;
import com.tradewind.common.quote.QuoteTick;
import com.tradewind.execution.engine.ExecutionEngine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.RangeAssignor;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One consumer, one thread, subscribed to orders.submitted and quotes.ticks. Both are keyed by symbol with the
 * same partition count, and RangeAssignor hands partition p of both topics to the same member, so the thread that
 * owns a symbol's orders also sees its ticks: a single writer per symbol with no locks (spec 4).
 * Offsets are committed only after the records' effects are committed in Postgres; on a processing failure the
 * partitions are rewound to the first unprocessed record.
 */
@Component
class ExecutionConsumer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ExecutionConsumer.class);
    private static final Duration POLL = Duration.ofMillis(100);
    private static final Duration COMMIT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration RETRY_BACKOFF = Duration.ofSeconds(1);
    private static final Duration EXPIRY_CHECK = Duration.ofSeconds(1);
    static final Duration MAX_TICK_AGE = Duration.ofSeconds(10);

    private final ExecutionEngine engine;
    private final String bootstrapServers;
    private final String groupId;
    private final Counter skipped;
    private final Counter staleTicks;
    private volatile boolean running;
    private volatile KafkaConsumer<String, String> consumer;
    private Thread thread;

    ExecutionConsumer(ExecutionEngine engine, @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
                      @Value("${tradewind.execution.consumer-group}") String groupId, MeterRegistry meters) {
        this.engine = engine;
        this.bootstrapServers = bootstrapServers;
        this.groupId = groupId;
        this.skipped = Counter.builder("execution.records.skipped").register(meters);
        this.staleTicks = Counter.builder("execution.ticks.stale").register(meters);
    }

    @Override
    public void start() {
        running = true;
        thread = Thread.ofPlatform().name("execution-engine").start(this::run);
    }

    @Override
    public void stop() {
        running = false;
        KafkaConsumer<String, String> current = consumer;
        if (current != null) {
            current.wakeup();
        }
        try {
            thread.join(Duration.ofSeconds(15));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void run() {
        if (!KafkaTopics.ensureWithRetry(bootstrapServers,
                List.of(Topics.ORDERS_SUBMITTED, Topics.QUOTES_TICKS, Topics.EXECUTIONS), () -> running)) {
            return;
        }
        Map<String, Object> props = KafkaDefaults.consumer(bootstrapServers, groupId);
        props.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, RangeAssignor.class.getName());
        // Never gives up while running: a failure (e.g. removed from the group after a slow batch, so the commit
        // fails) recreates the consumer. On re-join the books are rebuilt from resting_orders (Rebalance), and
        // uncommitted records are delivered again; processing is idempotent.
        while (running) {
            try (KafkaConsumer<String, String> kafka = new KafkaConsumer<>(props)) {
                consumer = kafka;
                kafka.subscribe(List.of(Topics.ORDERS_SUBMITTED.name(), Topics.QUOTES_TICKS.name()), new Rebalance());
                long nextExpiryCheck = 0;
                while (running) {
                    ConsumerRecords<String, String> records = kafka.poll(POLL);
                    process(kafka, records);
                    if (System.nanoTime() >= nextExpiryCheck) {
                        expireSafely();
                        nextExpiryCheck = System.nanoTime() + EXPIRY_CHECK.toNanos();
                    }
                }
            } catch (WakeupException e) {
                if (running) {
                    log.error("Execution consumer woken up unexpectedly, restarting", e);
                }
            } catch (RuntimeException e) {
                log.error("Execution consumer failed, restarting it: {}", e.toString());
                sleep(RETRY_BACKOFF);
            } finally {
                consumer = null;
            }
        }
    }

    private void process(KafkaConsumer<String, String> kafka, ConsumerRecords<String, String> records) {
        if (records.isEmpty()) {
            return;
        }
        Map<TopicPartition, OffsetAndMetadata> done = new HashMap<>();
        try {
            for (TopicPartition partition : records.partitions()) {
                for (ConsumerRecord<String, String> record : records.records(partition)) {
                    handle(record);
                    done.put(partition, new OffsetAndMetadata(record.offset() + 1));
                }
            }
        } catch (RuntimeException e) {
            log.warn("Processing failed, rewinding to the first unprocessed record: {}", e.toString());
            for (TopicPartition partition : records.partitions()) {
                OffsetAndMetadata next = done.get(partition);
                kafka.seek(partition, next != null ? next.offset() : records.records(partition).getFirst().offset());
            }
            sleep(RETRY_BACKOFF);
        }
        if (!done.isEmpty()) {
            kafka.commitSync(done, COMMIT_TIMEOUT);
        }
    }

    private void handle(ConsumerRecord<String, String> record) {
        EventEnvelope<JsonNode> event;
        try {
            event = EventJson.readRaw(record.value());
        } catch (RuntimeException e) {
            // A malformed record would otherwise block its partition forever.
            log.error("Skipping unreadable record {}-{}@{}: {}", record.topic(), record.partition(), record.offset(), e.toString());
            skipped.increment();
            return;
        }
        switch (event.eventType()) {
            case EventTypes.ORDER_ACCEPTED -> engine.onOrderAccepted(event.eventId(),
                    EventJson.convert(event.payload(), OrderAccepted.class), record.partition());
            case EventTypes.CANCEL_REQUESTED -> engine.onCancelRequested(event.eventId(),
                    EventJson.convert(event.payload(), CancelRequested.class));
            case EventTypes.QUOTE_TICK -> {
                // Old ticks (first start, catching up after an outage) would fill at prices that no longer exist.
                if (event.occurredAt().isBefore(Instant.now().minus(MAX_TICK_AGE))) {
                    staleTicks.increment();
                } else {
                    engine.onTick(EventJson.convert(event.payload(), QuoteTick.class));
                }
            }
            default -> log.debug("Ignoring event type {}", event.eventType());
        }
    }

    private void expireSafely() {
        try {
            engine.expireDayOrders();
        } catch (RuntimeException e) {
            log.warn("DAY order expiry failed, will retry: {}", e.toString());
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private final class Rebalance implements ConsumerRebalanceListener {

        @Override
        public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
            engine.onPartitionsRevoked(orderPartitions(partitions));
        }

        @Override
        public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
            Set<Integer> orderPartitions = orderPartitions(partitions);
            engine.onPartitionsAssigned(orderPartitions);
            log.info("Assigned order partitions {}; {} open orders loaded", orderPartitions, engine.openOrders());
        }

        private static Set<Integer> orderPartitions(Collection<TopicPartition> partitions) {
            return partitions.stream()
                    .filter(p -> p.topic().equals(Topics.ORDERS_SUBMITTED.name()))
                    .map(TopicPartition::partition)
                    .collect(Collectors.toSet());
        }
    }
}
