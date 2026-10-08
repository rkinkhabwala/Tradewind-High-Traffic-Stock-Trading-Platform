package com.tradewind.common.outbox;

import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Polling outbox relay (spec 11: polling in v1). Each poll runs in one transaction holding a Postgres advisory
 * lock, so across all instances of a service exactly one relay publishes at a time and rows go out in id order:
 * per-key ordering on Kafka matches commit order. Delivery is at-least-once (crash after send, before marking);
 * consumers deduplicate by event id.
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private record Row(long id, String topic, String key, String payload) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final Producer<String, String> producer;
    private final long lockKey;
    private final int batchSize;
    private final Duration sendTimeout;
    private final Clock clock;

    public OutboxRelay(JdbcClient jdbc, TransactionTemplate transactions, Producer<String, String> producer,
                       long lockKey, int batchSize, Duration sendTimeout, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.producer = producer;
        this.lockKey = lockKey;
        this.batchSize = batchSize;
        this.sendTimeout = sendTimeout;
        this.clock = clock;
    }

    /** Publishes up to one batch. Returns how many rows were published (0 if another instance holds the lock). */
    public int relayOnce() {
        Integer published = transactions.execute(status -> {
            boolean leader = jdbc.sql("SELECT pg_try_advisory_xact_lock(:key)").param("key", lockKey)
                    .query(Boolean.class).single();
            if (!leader) {
                return 0;
            }
            List<Row> rows = jdbc.sql("""
                            SELECT id, topic, event_key, payload_json FROM outbox
                            WHERE published_at IS NULL ORDER BY id LIMIT :limit""")
                    .param("limit", batchSize)
                    .query((rs, n) -> new Row(rs.getLong("id"), rs.getString("topic"), rs.getString("event_key"),
                            rs.getString("payload_json")))
                    .list();
            if (rows.isEmpty()) {
                return 0;
            }
            List<Future<RecordMetadata>> sends = new ArrayList<>(rows.size());
            for (Row row : rows) {
                sends.add(producer.send(new ProducerRecord<>(row.topic(), row.key(), row.payload())));
            }
            // Only the prefix that was acknowledged is marked, so a failure never lets a later event overtake.
            List<Long> acknowledged = new ArrayList<>();
            long deadline = System.nanoTime() + sendTimeout.toNanos();
            for (int i = 0; i < rows.size(); i++) {
                try {
                    sends.get(i).get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                    acknowledged.add(rows.get(i).id());
                } catch (Exception e) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    log.warn("Outbox publish stopped at id {}: {}", rows.get(i).id(), e.toString());
                    break;
                }
            }
            if (!acknowledged.isEmpty()) {
                jdbc.sql("UPDATE outbox SET published_at = :now WHERE id IN (:ids)")
                        .param("now", Timestamp.from(clock.instant())).param("ids", acknowledged)
                        .update();
            }
            return acknowledged.size();
        });
        return published == null ? 0 : published;
    }

    /**
     * Keeps the table small; published rows are only needed for debugging. Deletes in batches of 5,000 so no single
     * transaction holds locks or WAL for long (at load-test rates the table gains hundreds of thousands of rows an
     * hour, and an unbounded table pushes the hot data out of Postgres' cache). Returns the rows deleted.
     */
    public int purgePublishedBefore(Instant cutoff) {
        int total = 0;
        int deleted;
        do {
            deleted = jdbc.sql("""
                            DELETE FROM outbox WHERE id IN (
                                SELECT id FROM outbox WHERE published_at IS NOT NULL AND published_at < :cutoff LIMIT 5000)""")
                    .param("cutoff", Timestamp.from(cutoff))
                    .update();
            total += deleted;
        } while (deleted == 5000);
        return total;
    }
}
