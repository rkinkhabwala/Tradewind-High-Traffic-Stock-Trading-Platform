package com.tradewind.common.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.tradewind.common.event.EventEnvelope;
import com.tradewind.common.event.EventJson;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * At-least-once consumer loop for idempotent handlers (spec 4): each record is handled (the handler commits its
 * own DB transaction, including the processed-event marker), then offsets are committed. If a handler throws,
 * every partition of the batch is rewound to its first unprocessed record and retried after a backoff, so
 * nothing is skipped. Unreadable records are logged and skipped instead of blocking their partition forever.
 */
public final class EventConsumerLoop implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(EventConsumerLoop.class);
    private static final Duration POLL = Duration.ofMillis(100);
    private static final Duration COMMIT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration RETRY_BACKOFF = Duration.ofSeconds(1);

    @FunctionalInterface
    public interface Handler {
        void handle(EventEnvelope<JsonNode> event, ConsumerRecord<String, String> record);
    }

    private final String name;
    private final String bootstrapServers;
    private final Map<String, Object> properties;
    private final List<TopicSpec> topics;
    private final Handler handler;
    private volatile boolean running;
    private volatile KafkaConsumer<String, String> consumer;
    private final java.util.concurrent.atomic.AtomicInteger restarts = new java.util.concurrent.atomic.AtomicInteger();
    private Thread thread;

    public EventConsumerLoop(String name, String bootstrapServers, String groupId, List<TopicSpec> topics,
                             Handler handler) {
        this(name, bootstrapServers, groupId, topics, handler, Map.of());
    }

    /** {@code overrides} replace consumer settings (tests use a short max.poll.interval.ms). */
    public EventConsumerLoop(String name, String bootstrapServers, String groupId, List<TopicSpec> topics,
                             Handler handler, Map<String, Object> overrides) {
        this.name = name;
        this.bootstrapServers = bootstrapServers;
        this.properties = new HashMap<>(KafkaDefaults.consumer(bootstrapServers, groupId));
        this.properties.putAll(overrides);
        this.topics = topics;
        this.handler = handler;
    }

    /** How often the consumer was recreated after an unexpected failure. */
    public int restarts() {
        return restarts.get();
    }

    public void start() {
        running = true;
        thread = Thread.ofPlatform().name(name).start(this::run);
    }

    public boolean isRunning() {
        return running;
    }

    @Override
    public void close() {
        running = false;
        KafkaConsumer<String, String> current = consumer;
        if (current != null) {
            current.wakeup();
        }
        if (thread != null) {
            try {
                thread.join(Duration.ofSeconds(15));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Never gives up while running: any unexpected failure (e.g. the consumer was removed from the group after a
     * slow batch, so the commit fails) closes the consumer, backs off and re-joins. Uncommitted records are
     * delivered again; handlers are idempotent (processed-event markers), so nothing is lost or applied twice.
     */
    private void run() {
        if (!KafkaTopics.ensureWithRetry(bootstrapServers, topics, () -> running)) {
            return;
        }
        while (running) {
            try (KafkaConsumer<String, String> kafka = new KafkaConsumer<>(properties)) {
                consumer = kafka;
                kafka.subscribe(topics.stream().map(TopicSpec::name).toList());
                while (running) {
                    process(kafka, kafka.poll(POLL));
                }
            } catch (WakeupException e) {
                if (running) {
                    log.error("{} woken up unexpectedly, restarting", name, e);
                    restarts.incrementAndGet();
                }
            } catch (RuntimeException e) {
                log.error("{} failed, restarting the consumer: {}", name, e.toString());
                restarts.incrementAndGet();
                sleep();
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
                    EventEnvelope<JsonNode> event = read(record);
                    if (event != null) {
                        handler.handle(event, record);
                    }
                    done.put(partition, new OffsetAndMetadata(record.offset() + 1));
                }
            }
        } catch (RuntimeException e) {
            log.warn("{}: processing failed, rewinding to the first unprocessed record: {}", name, e.toString());
            for (TopicPartition partition : records.partitions()) {
                OffsetAndMetadata next = done.get(partition);
                kafka.seek(partition, next != null ? next.offset() : records.records(partition).getFirst().offset());
            }
            sleep();
        }
        if (!done.isEmpty()) {
            kafka.commitSync(done, COMMIT_TIMEOUT);
        }
    }

    private EventEnvelope<JsonNode> read(ConsumerRecord<String, String> record) {
        try {
            return EventJson.readRaw(record.value());
        } catch (RuntimeException e) {
            log.error("{}: skipping unreadable record {}-{}@{}: {}", name, record.topic(), record.partition(),
                    record.offset(), e.toString());
            return null;
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(RETRY_BACKOFF);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
