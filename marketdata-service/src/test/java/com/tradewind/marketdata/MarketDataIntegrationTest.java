package com.tradewind.marketdata;

import com.tradewind.common.event.EventEnvelope;
import com.tradewind.common.event.EventJson;
import com.tradewind.common.event.EventTypes;
import com.tradewind.common.kafka.KafkaDefaults;
import com.tradewind.common.quote.QuoteTick;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The whole service against real Postgres, Redis and Kafka. FAST_CLOCK at 60x starting mid-session, so one real
 * second is one simulated minute and candles appear quickly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ContainersConfiguration.class)
class MarketDataIntegrationTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("tradewind.clock.mode", () -> "FAST");
        registry.add("tradewind.clock.speed", () -> "60");
        registry.add("tradewind.clock.sim-start", () -> "2026-01-05T10:00:00-05:00");
        registry.add("tradewind.clock.real-epoch", () -> Instant.now().toString());
        registry.add("tradewind.simulator.tick-rate-min", () -> "1");
        registry.add("tradewind.simulator.tick-rate-max", () -> "2");
        registry.add("tradewind.simulator.hot-symbol", () -> "GME");
        registry.add("tradewind.simulator.candle-flush-interval", () -> "500ms");
    }

    @Autowired
    KafkaContainer kafkaContainer;
    @Autowired
    TestRestTemplate http;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    JdbcClient jdbc;

    @Test
    void seedsFiveHundredSymbolsAndPublishesTheDirectory() {
        assertThat(jdbc.sql("SELECT count(*) FROM symbols").query(Long.class).single()).isEqualTo(500);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(redis.opsForSet().size("symbols")).isEqualTo(500));
    }

    @Test
    void latestQuotesLandInRedis() {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Map<Object, Object> quote = redis.opsForHash().entries("quote:AAPL");
            assertThat(quote).containsKeys("bid", "ask", "last", "bidSize", "askSize", "ts", "prevClose");
            assertThat(new BigDecimal((String) quote.get("bid"))).isLessThan(new BigDecimal((String) quote.get("ask")));
        });
    }

    @Test
    void ticksArePublishedToKafkaKeyedBySymbolOnTwelvePartitions() throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafkaContainer.getBootstrapServers()))) {
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(admin.describeTopics(Set.of("quotes.ticks"))
                    .allTopicNames().get().get("quotes.ticks").partitions()).hasSize(12));
        }
        // A steady-state sample from every partition: the first records of a subscription come in whole batches
        // from a few partitions, which says nothing about the mix of symbols.
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer =
                     new KafkaConsumer<>(KafkaDefaults.consumer(kafkaContainer.getBootstrapServers(), "test-" + System.nanoTime()))) {
            List<org.apache.kafka.common.TopicPartition> partitions = consumer.partitionsFor("quotes.ticks").stream()
                    .map(p -> new org.apache.kafka.common.TopicPartition(p.topic(), p.partition())).toList();
            consumer.assign(partitions);
            consumer.seekToEnd(partitions);
            partitions.forEach(consumer::position);
            long until = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (System.nanoTime() < until) {
                consumer.poll(Duration.ofMillis(200)).forEach(records::add);
            }
        }
        assertThat(records).hasSizeGreaterThan(200);
        ConsumerRecord<String, String> record = records.getFirst();
        EventEnvelope<QuoteTick> event = EventJson.read(record.value(), QuoteTick.class);
        assertThat(event.eventType()).isEqualTo(EventTypes.QUOTE_TICK);
        assertThat(event.key()).isEqualTo(record.key()).isEqualTo(event.payload().symbol());
        // Hot symbol mode: GME ticks ~100x/s, far more than anything else (others 1-2/s here). Compared per symbol:
        // GME's share of ALL ticks is only ~12% with 499 other symbols, too close to any fixed share to assert on.
        Map<String, Long> perSymbol = records.stream()
                .collect(java.util.stream.Collectors.groupingBy(ConsumerRecord::key, java.util.stream.Collectors.counting()));
        long gme = perSymbol.getOrDefault("GME", 0L);
        long busiestOther = perSymbol.entrySet().stream().filter(e -> !e.getKey().equals("GME"))
                .mapToLong(Map.Entry::getValue).max().orElse(0);
        assertThat(gme).isGreaterThan(10 * Math.max(1, busiestOther));
    }

    @Test
    void minuteCandlesAreWrittenAndAggregated() {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(jdbc.sql(
                "SELECT count(DISTINCT bucket_start) FROM candles_1m WHERE symbol = 'GME'").query(Long.class).single())
                .isGreaterThanOrEqualTo(3));

        List<Map> oneMinute = List.of(http.getForObject("/api/symbols/GME/candles?interval=1m", Map[].class));
        List<Map> fiveMinute = List.of(http.getForObject("/api/symbols/GME/candles?interval=5m", Map[].class));

        assertThat(oneMinute).hasSizeGreaterThanOrEqualTo(3);
        assertThat(fiveMinute).isNotEmpty().hasSizeLessThan(oneMinute.size());
        Map<?, ?> candle = oneMinute.getFirst();
        assertThat(new BigDecimal((String) candle.get("high"))).isGreaterThanOrEqualTo(new BigDecimal((String) candle.get("low")));
        assertThat(http.getForEntity("/api/symbols/GME/candles?interval=7m", Map.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void quoteSymbolAndSearchEndpoints() {
        await().atMost(Duration.ofSeconds(10)).until(() -> redis.hasKey("quote:TSLA") && redis.hasKey("quote:AAPL"));

        List<Map> quotes = List.of(http.getForObject("/api/quotes?symbols=AAPL, tsla,NOPE", Map[].class));
        assertThat(quotes).extracting(q -> q.get("symbol")).containsExactlyInAnyOrder("AAPL", "TSLA");

        @SuppressWarnings("unchecked")
        Map<String, Object> symbol = http.getForObject("/api/symbols/aapl", Map.class);
        assertThat(symbol).containsEntry("symbol", "AAPL").containsEntry("name", "Apple Inc.")
                .containsKeys("last", "prevClose", "dayChange", "dayChangePercent");
        assertThat(http.getForEntity("/api/symbols/NOPE", Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        List<Map> search = List.of(http.getForObject("/api/search?q=app", Map[].class));
        assertThat(search).extracting(r -> r.get("symbol")).contains("AAPL");
    }
}
