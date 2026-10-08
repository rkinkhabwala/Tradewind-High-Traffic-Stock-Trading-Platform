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
    /** When set, each poll's records are applied in one database transaction (see {@link #withBatchTransactions}). */
    private volatile BatchTransaction batchTransaction;
    private volatile int maxBatch = 25;
    private volatile boolean running;
    private volatile KafkaConsumer<String, String> consumer;
    private final java.util.concurrent.atomic.AtomicInteger restarts = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger batches = new java.util.concurrent.atomic.AtomicInteger();
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

    /** Runs work in one database transaction (e.g. a TransactionTemplate); kept generic to avoid a Spring dependency. */
    @FunctionalInterface
    public interface BatchTransaction {
        void inTransaction(Runnable work);
    }

    /**
     * Applies all records of a poll in one transaction instead of one per record, so a busy consumer pays one commit
     * (one WAL sync) per batch. Handlers must take part in an existing transaction (Spring's default propagation).
     * If anything in the batch fails, the whole batch is rolled back and then applied record by record as without
     * this option, so one bad record is still isolated and retried on its own.
     */
    public EventConsumerLoop withBatchTransactions(BatchTransaction transaction) {
        return withBatchTransactions(transaction, 25);
    }

    /**
     * {@code maxBatch} bounds how many records share one transaction: a transaction holds its row locks until it
     * commits, and those rows (an account's balances, an order) are also wanted by the request path. Measured:
     * whole polls (up to 500 fills) made reservations wait behind fill batches; small chunks keep locks short and
     * still cut commits by about that factor.
     */
    public EventConsumerLoop withBatchTransactions(BatchTransaction transaction, int maxBatch) {
        if (maxBatch < 1) {
            throw new IllegalArgumentException("maxBatch must be positive");
        }
        this.batchTransaction = transaction;
        this.maxBatch = maxBatch;
        return this;
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
        process(kafka, records, true);
    }

    private void process(KafkaConsumer<String, String> kafka, ConsumerRecords<String, String> records, boolean allowBatch) {
        if (records.isEmpty()) {
            return;
        }
        BatchTransaction batch = batchTransaction;
        if (allowBatch && batch != null && records.count() > 1) {
            processInChunks(kafka, batch, records);
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

    /**
     * Applies the poll in chunks of at most {@code maxBatch}, one transaction each, committing offsets after each
     * chunk. A chunk that fails is rolled back and the rest of the poll goes record by record (rewind on failure).
     */
    private void processInChunks(KafkaConsumer<String, String> kafka, BatchTransaction batch,
                                 ConsumerRecords<String, String> records) {
        List<ConsumerRecord<String, String>> all = new java.util.ArrayList<>(records.count());
        records.forEach(all::add);
        for (int from = 0; from < all.size(); from += maxBatch) {
            List<ConsumerRecord<String, String>> chunk = all.subList(from, Math.min(all.size(), from + maxBatch));
            if (chunk.size() > 1 && processBatch(batch, chunk)) {
                kafka.commitSync(nextOffsets(chunk), COMMIT_TIMEOUT);
                continue;
            }
            // Record by record from here, exactly as without batching (rewinds the partitions on failure).
            Map<TopicPartition, List<ConsumerRecord<String, String>>> rest = new java.util.LinkedHashMap<>();
            all.subList(from, all.size()).forEach(r ->
                    rest.computeIfAbsent(new TopicPartition(r.topic(), r.partition()), p -> new java.util.ArrayList<>()).add(r));
            process(kafka, new ConsumerRecords<>(rest), false);
            return;
        }
    }

    private static Map<TopicPartition, OffsetAndMetadata> nextOffsets(List<ConsumerRecord<String, String>> chunk) {
        Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
        for (ConsumerRecord<String, String> r : chunk) {
            offsets.put(new TopicPartition(r.topic(), r.partition()), new OffsetAndMetadata(r.offset() + 1));
        }
        return offsets;
    }

    /** True if the records were applied in one transaction; false if it was rolled back. */
    private boolean processBatch(BatchTransaction batch, List<ConsumerRecord<String, String>> records) {
        try {
            batch.inTransaction(() -> {
                for (ConsumerRecord<String, String> record : records) {
                    EventEnvelope<JsonNode> event = read(record);
                    if (event != null) {
                        handler.handle(event, record);
                    }
                }
            });
            batches.incrementAndGet();
            return true;
        } catch (RuntimeException e) {
            log.debug("{}: batch of {} rolled back, applying record by record: {}", name, records.size(), e.toString());
            return false;
        }
    }

    /** Polls applied as one transaction. */
    public int batches() {
        return batches.get();
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
