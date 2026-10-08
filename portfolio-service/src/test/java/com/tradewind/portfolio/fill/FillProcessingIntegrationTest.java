package com.tradewind.portfolio.fill;

import com.tradewind.common.event.EventEnvelope;
import com.tradewind.common.event.EventJson;
import com.tradewind.common.event.EventTypes;
import com.tradewind.common.execution.ExecutionReported;
import com.tradewind.common.execution.OrderCanceled;
import com.tradewind.common.execution.OrderExpired;
import com.tradewind.common.kafka.KafkaDefaults;
import com.tradewind.common.kafka.Topics;
import com.tradewind.common.money.Price;
import com.tradewind.common.money.Quantity;
import com.tradewind.common.order.OrderSide;
import com.tradewind.portfolio.PostgresTestConfiguration;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Plays execution-service on the executions topic and checks the books: ledger balances, reservations, positions,
 * PortfolioUpdated events and reconciliation, all against real Postgres and Kafka.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
class FillProcessingIntegrationTest {

    @Autowired
    TestRestTemplate http;
    @Autowired
    KafkaContainer kafka;
    @Autowired
    org.springframework.data.redis.connection.RedisConnectionFactory redisConnections;

    private KafkaProducer<String, String> producer;
    private UUID account;

    @BeforeEach
    void setUp() {
        if (producer == null) {
            producer = new KafkaProducer<>(KafkaDefaults.producer(kafka.getBootstrapServers()));
        }
        account = UUID.randomUUID();
        assertThat(http.postForEntity("/internal/ledger/deposits",
                Map.of("referenceId", UUID.randomUUID(), "accountId", account, "amountCents", 10_000_000), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private UUID reserveCash(long cents) {
        UUID order = UUID.randomUUID();
        assertThat(http.postForEntity("/internal/reservations",
                Map.of("orderId", order, "accountId", account, "cashCents", cents), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        return order;
    }

    private UUID reserveShares(String symbol, String qty) {
        UUID order = UUID.randomUUID();
        assertThat(http.postForEntity("/internal/reservations",
                Map.of("orderId", order, "accountId", account, "symbol", symbol, "quantity", qty), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        return order;
    }

    private EventEnvelope<ExecutionReported> fill(UUID order, OrderSide side, String symbol, int seq, String qty,
                                                  String price, String cumulative, String remaining) {
        UUID executionId = UUID.nameUUIDFromBytes(("execution:" + order + ":" + seq).getBytes());
        ExecutionReported e = new ExecutionReported(executionId, order, account, symbol, side, seq, Quantity.of(qty),
                Price.of(price), Quantity.of(cumulative), Quantity.of(remaining), Quantity.of(remaining).isZero(),
                Instant.now());
        return EventEnvelope.of(EventTypes.EXECUTION_REPORTED, 1, account.toString(), e, Clock.systemUTC());
    }

    private void send(EventEnvelope<?> event) {
        try {
            producer.send(new ProducerRecord<>(Topics.EXECUTIONS.name(), account.toString(), EventJson.write(event))).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<?, ?> balances() {
        return http.getForObject("/internal/accounts/" + account + "/balances", Map.class);
    }

    private long cash() {
        return ((Number) balances().get("cashCents")).longValue();
    }

    private long reservedCash() {
        return ((Number) balances().get("cashReservedCents")).longValue();
    }

    private Map<?, ?> position(String symbol) {
        return ((List<?>) balances().get("positions")).stream().map(p -> (Map<?, ?>) p)
                .filter(p -> symbol.equals(p.get("symbol"))).findFirst().orElse(null);
    }

    private Map<?, ?> reservation(UUID order) {
        // The release endpoint is idempotent and returns the current state; only call it once the order is closed.
        return http.postForObject("/internal/reservations/" + order + "/release", null, Map.class);
    }

    private void assertReconciles() {
        Map<?, ?> report = http.getForObject("/internal/reconciliation", Map.class);
        assertThat(report.get("ok")).as(report.toString()).isEqualTo(true);
    }

    // ---- tests -----------------------------------------------------------------------------------------------

    @Test
    void buyFillsMoveExactlyTheFilledAmountAndReleaseTheRest() {
        UUID order = reserveCash(210_011);     // 10 x 200.01 x 1.05, as order-service sizes a market buy

        send(fill(order, OrderSide.BUY, "AAPL", 1, "4", "200.01", "4", "6"));
        send(fill(order, OrderSide.BUY, "AAPL", 2, "6", "200.02", "10", "0"));

        // 4 x 200.01 + 6 x 200.02 = 2000.16; the unused 100.95 of the reservation goes back to cash.
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(cash()).isEqualTo(10_000_000 - 200_016);
            assertThat(reservedCash()).isZero();
        });
        Map<?, ?> position = position("AAPL");
        assertThat(position.get("quantity")).isEqualTo("10");
        assertThat(position.get("avgCost")).isEqualTo("200.0160");
        assertThat(reservation(order).get("status")).isEqualTo("CLOSED");
        assertReconciles();
    }

    @Test
    void sellFillCreditsProceedsAndKeepsAverageCost() {
        UUID buy = reserveCash(105_000);
        send(fill(buy, OrderSide.BUY, "MSFT", 1, "10", "100.00", "10", "0"));
        await().atMost(Duration.ofSeconds(20)).until(() -> position("MSFT") != null);

        UUID sell = reserveShares("MSFT", "4");
        send(fill(sell, OrderSide.SELL, "MSFT", 1, "4", "125.50", "4", "0"));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(position("MSFT").get("quantity")).isEqualTo("6"));
        assertThat(position("MSFT").get("avgCost")).isEqualTo("100.0000");
        assertThat(cash()).isEqualTo(10_000_000 - 100_000 + 50_200);
        assertReconciles();
    }

    @Test
    void cancelAfterAPartialFillReleasesTheUnfilledPart() {
        UUID order = reserveCash(100_000);     // limit buy 10 @ 100.00

        send(fill(order, OrderSide.BUY, "TSLA", 1, "3", "99.50", "3", "7"));
        send(EventEnvelope.of(EventTypes.ORDER_CANCELED, 1, account.toString(), new OrderCanceled(order, account,
                "TSLA", OrderSide.BUY, Quantity.of("7"), Quantity.of("3"), "USER_REQUESTED", Instant.now()),
                Clock.systemUTC()));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(cash()).isEqualTo(10_000_000 - 29_850);
            assertThat(reservedCash()).isZero();
        });
        assertThat(position("TSLA").get("quantity")).isEqualTo("3");
        assertReconciles();
    }

    @Test
    void expiryReleasesTheWholeReservation() {
        UUID order = reserveCash(50_000);

        send(EventEnvelope.of(EventTypes.ORDER_EXPIRED, 1, account.toString(), new OrderExpired(order, account,
                "GME", OrderSide.BUY, Quantity.of("20"), Quantity.ZERO, LocalDate.now(), Instant.now()),
                Clock.systemUTC()));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(cash()).isEqualTo(10_000_000);
            assertThat(reservedCash()).isZero();
        });
        assertReconciles();
    }

    @Test
    void redeliveredEventsAreAppliedOnce() {
        UUID order = reserveCash(30_000);
        EventEnvelope<ExecutionReported> event = fill(order, OrderSide.BUY, "NVDA", 1, "2", "120.00", "2", "0");

        send(event);
        send(event);
        send(event);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(reservedCash()).isZero());
        assertThat(cash()).isEqualTo(10_000_000 - 24_000);
        assertThat(position("NVDA").get("quantity")).isEqualTo("2");
        assertReconciles();
    }

    @Test
    void everyLedgerChangeIsPublishedAsPortfolioUpdatedKeyedByAccount() {
        UUID order = reserveCash(21_000);
        send(fill(order, OrderSide.BUY, "AMD", 1, "1", "200.00", "1", "0"));
        await().atMost(Duration.ofSeconds(20)).until(() -> position("AMD") != null);

        List<EventEnvelope<Map>> updates = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(
                KafkaDefaults.consumer(kafka.getBootstrapServers(), "test-" + UUID.randomUUID()))) {
            consumer.subscribe(List.of(Topics.LEDGER_EVENTS.name()));
            await().atMost(Duration.ofSeconds(20)).until(() -> {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(200))) {
                    if (r.key().equals(account.toString())) {
                        updates.add(EventJson.read(r.value(), Map.class));
                    }
                }
                return updates.size() >= 4;
            });
        }
        assertThat(updates).extracting(e -> e.payload().get("reason"))
                .containsExactly("DEPOSIT", "RESERVE", "RELEASE", "FILL");
        Map<?, ?> last = updates.getLast().payload();
        assertThat(last.get("cash")).isEqualTo(10_000_000 - 20_000);
        assertThat(last.get("symbol")).isEqualTo("AMD");
        assertThat(last.get("positionQty")).isEqualTo("1.000000");
    }

    /** Subscribes to the account's Redis channel, as stream-service does, for the duration of a test. */
    static final class UserChannel implements AutoCloseable {
        final List<Map<?, ?>> messages = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final org.springframework.data.redis.listener.RedisMessageListenerContainer container =
                new org.springframework.data.redis.listener.RedisMessageListenerContainer();

        UserChannel(org.springframework.data.redis.connection.RedisConnectionFactory redis, UUID account) {
            container.setConnectionFactory(redis);
            container.addMessageListener((message, pattern) -> messages.add(
                            EventJson.newMapper().convertValue(parse(message.getBody()), Map.class)),
                    new org.springframework.data.redis.listener.ChannelTopic("user:" + account));
            container.afterPropertiesSet();
            container.start();
        }

        private static com.fasterxml.jackson.databind.JsonNode parse(byte[] body) {
            try {
                return EventJson.newMapper().readTree(body);
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }

        List<Object> field(String type, String key) {
            return messages.stream().filter(m -> type.equals(m.get("type"))).<Object>map(m -> m.get(key)).toList();
        }

        @Override
        public void close() throws Exception {
            container.destroy();
        }
    }

    @Test
    void everyCommittedLedgerChangeIsPushedToTheAccountChannel() throws Exception {
        try (UserChannel channel = new UserChannel(redisConnections, account)) {
            UUID order = reserveCash(210_011);
            send(fill(order, OrderSide.BUY, "AAPL", 1, "10", "200.01", "10", "0"));

            await().atMost(Duration.ofSeconds(20)).until(() -> channel.field("portfolio", "reason").contains("FILL"));
            Map<?, ?> reserve = channel.messages.getFirst();
            assertThat(reserve.get("reason")).isEqualTo("RESERVE");
            assertThat(((Number) reserve.get("cashReservedCents")).longValue()).isEqualTo(210_011);
            assertThat(((Number) reserve.get("buyingPowerCents")).longValue()).isEqualTo(10_000_000 - 210_011);

            Map<?, ?> fill = channel.messages.stream().filter(m -> "FILL".equals(m.get("reason"))).findFirst().orElseThrow();
            assertThat(((Number) fill.get("cashCents")).longValue()).isEqualTo(10_000_000 - 200_010);
            assertThat(((Number) fill.get("cashReservedCents")).longValue()).isZero();
            assertThat(fill.get("symbol")).isEqualTo("AAPL");
            assertThat(fill.get("positionQty")).isEqualTo("10.000000");
            assertThat(fill.get("type")).isEqualTo("portfolio");
        }
    }
}
