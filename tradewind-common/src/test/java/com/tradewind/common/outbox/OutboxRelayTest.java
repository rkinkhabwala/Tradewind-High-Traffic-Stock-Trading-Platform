package com.tradewind.common.outbox;

import com.tradewind.common.event.EventEnvelope;
import com.tradewind.common.kafka.KafkaDefaults;
import com.tradewind.common.kafka.TopicSpec;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class OutboxRelayTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.15");
    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

    static final TopicSpec TOPIC = new TopicSpec("outbox.test", 3);
    static JdbcClient jdbc;
    static TransactionTemplate transactions;
    static KafkaProducer<String, String> producer;

    record Payload(int sequence) {
    }

    @BeforeAll
    static void setUp() throws Exception {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        jdbc = JdbcClient.create(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jdbc.sql("""
                CREATE TABLE outbox (
                    id BIGSERIAL PRIMARY KEY, event_id UUID NOT NULL UNIQUE, aggregate_id VARCHAR(64) NOT NULL,
                    event_type VARCHAR(64) NOT NULL, topic VARCHAR(64) NOT NULL, event_key VARCHAR(64) NOT NULL,
                    payload_json TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL, published_at TIMESTAMPTZ)""").update();
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC.name(), TOPIC.partitions(), (short) 1))).all().get();
        }
        producer = new KafkaProducer<>(KafkaDefaults.producer(KAFKA.getBootstrapServers()));
    }

    @AfterAll
    static void tearDown() {
        producer.close();
    }

    @Test
    void competingRelaysPublishEveryEventOnceInPerKeyOrder() throws Exception {
        OutboxWriter writer = new OutboxWriter(jdbc);
        int perKey = 200;
        for (int i = 0; i < perKey; i++) {
            for (String key : List.of("AAPL", "TSLA", "GME")) {
                int sequence = i;
                transactions.executeWithoutResult(s -> writer.append(TOPIC, key + "-" + sequence,
                        EventEnvelope.of("Test", 1, key, new Payload(sequence), Clock.systemUTC())));
            }
        }

        // Three instances polling concurrently, small batches to force many interleavings.
        List<OutboxRelay> relays = List.of(relay(), relay(), relay());
        try (ExecutorService pool = Executors.newFixedThreadPool(3)) {
            List<Future<?>> runs = new ArrayList<>();
            for (OutboxRelay relay : relays) {
                runs.add(pool.submit(() -> {
                    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
                    while (unpublished() > 0 && System.nanoTime() < deadline) {
                        relay.relayOnce();
                    }
                }));
            }
            for (Future<?> run : runs) {
                run.get();
            }
        }

        List<ConsumerRecord<String, String>> records = consumeAll(perKey * 3);
        assertThat(records).hasSize(perKey * 3);
        Map<String, List<Integer>> sequencesByKey = records.stream().collect(Collectors.groupingBy(ConsumerRecord::key,
                Collectors.mapping(r -> Integer.parseInt(r.value().replaceAll(".*\"sequence\":(\\d+).*", "$1")),
                        Collectors.toList())));
        for (List<Integer> sequences : sequencesByKey.values()) {
            assertThat(sequences).isSorted().doesNotHaveDuplicates().hasSize(perKey);
        }
        assertThat(unpublished()).isZero();
    }

    @Test
    void purgesOnlyPublishedRows() {
        int purged = relay().purgePublishedBefore(java.time.Instant.now().plusSeconds(60));
        assertThat(purged).isGreaterThanOrEqualTo(0);
        assertThat(jdbc.sql("SELECT count(*) FROM outbox WHERE published_at IS NOT NULL").query(Long.class).single())
                .isZero();
    }

    private static OutboxRelay relay() {
        return new OutboxRelay(jdbc, transactions, producer, 4242L, 25, Duration.ofSeconds(10), Clock.systemUTC());
    }

    private static long unpublished() {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE published_at IS NULL").query(Long.class).single();
    }

    private static List<ConsumerRecord<String, String>> consumeAll(int expected) {
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer =
                     new KafkaConsumer<>(KafkaDefaults.consumer(KAFKA.getBootstrapServers(), "outbox-test"))) {
            consumer.subscribe(List.of(TOPIC.name()));
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (records.size() < expected && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(200)).forEach(records::add);
            }
            // Give duplicates, if any, a chance to show up.
            consumer.poll(Duration.ofSeconds(1)).forEach(records::add);
        }
        return records;
    }
}
