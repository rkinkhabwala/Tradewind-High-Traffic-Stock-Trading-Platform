package com.tradewind.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.tradewind.common.event.EventEnvelope;
import com.tradewind.common.event.EventJson;
import com.tradewind.common.event.EventTypes;
import com.tradewind.common.kafka.KafkaDefaults;
import com.tradewind.common.kafka.Topics;
import com.tradewind.common.money.Price;
import com.tradewind.common.money.Quantity;
import com.tradewind.common.order.CancelRequested;
import com.tradewind.common.order.OrderAccepted;
import com.tradewind.common.order.OrderSide;
import com.tradewind.common.order.OrderType;
import com.tradewind.common.order.TimeInForce;
import com.tradewind.common.quote.QuoteHash;
import com.tradewind.common.quote.QuoteTick;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The test plays order-service (orders.submitted) and marketdata-service (quotes.ticks, Redis quotes) over real
 * Kafka, and reads what execution-service publishes on executions.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestInfrastructure.class)
class ExecutionFlowIntegrationTest {

    @Autowired
    KafkaContainer kafka;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    TestInfrastructure.TestClock clock;
    @Autowired
    List<SmartLifecycle> lifecycles;

    static KafkaProducer<String, String> producer;
    static KafkaConsumer<String, String> executions;
    static final Map<String, List<ConsumerRecord<String, String>>> received = new ConcurrentHashMap<>();
    static Thread collector;
    static volatile boolean collecting = true;

    private final UUID account = UUID.randomUUID();

    @BeforeAll
    static void noop() {
    }

    @BeforeEach
    void setUp() {
        clock.now.set(TestInfrastructure.MONDAY_10AM);
        if (producer == null) {
            producer = new KafkaProducer<>(KafkaDefaults.producer(kafka.getBootstrapServers()));
            executions = new KafkaConsumer<>(KafkaDefaults.consumer(kafka.getBootstrapServers(), "test-collector"));
            executions.subscribe(List.of(Topics.EXECUTIONS.name()));
            collector = Thread.ofPlatform().daemon().start(() -> {
                while (collecting) {
                    for (ConsumerRecord<String, String> r : executions.poll(Duration.ofMillis(100))) {
                        String orderId = EventJson.readRaw(r.value()).payload().get("orderId").asText();
                        received.computeIfAbsent(orderId, k -> new CopyOnWriteArrayList<>()).add(r);
                    }
                }
            });
        }
        // Give the engine's consumer time to join and get its partitions before the first test sends anything.
        await().atMost(Duration.ofSeconds(30)).until(() -> jdbc.sql("SELECT 1").query(Integer.class).single() == 1);
    }

    @AfterAll
    static void tearDown() {
        collecting = false;
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private void quote(String symbol, String bid, long bidSize, String ask, long askSize) {
        QuoteTick tick = new QuoteTick(symbol, Price.of(bid), Price.of(ask), Price.of(bid), bidSize, askSize, 10,
                clock.now());
        redis.opsForHash().putAll(QuoteHash.key(symbol), QuoteHash.toHash(tick, Price.of(bid)));
    }

    private void tick(String symbol, String bid, long bidSize, String ask, long askSize) {
        QuoteTick tick = new QuoteTick(symbol, Price.of(bid), Price.of(ask), Price.of(bid), bidSize, askSize, 10,
                clock.now());
        send(Topics.QUOTES_TICKS.name(), symbol,
                EventEnvelope.of(EventTypes.QUOTE_TICK, 1, symbol, tick, Clock.systemUTC()));
    }

    private UUID accept(String symbol, OrderSide side, OrderType type, TimeInForce tif, String qty, String limit,
                        String cap) {
        UUID orderId = UUID.randomUUID();
        send(Topics.ORDERS_SUBMITTED.name(), symbol, acceptedEvent(orderId, symbol, side, type, tif, qty, limit, cap));
        return orderId;
    }

    private EventEnvelope<OrderAccepted> acceptedEvent(UUID orderId, String symbol, OrderSide side, OrderType type,
                                                       TimeInForce tif, String qty, String limit, String cap) {
        OrderAccepted accepted = new OrderAccepted(orderId, account, symbol, side, type, tif, Quantity.of(qty),
                limit == null ? null : Price.of(limit), cap == null ? null : Price.of(cap), Instant.now());
        return EventEnvelope.of(EventTypes.ORDER_ACCEPTED, 1, symbol, accepted, Clock.systemUTC());
    }

    private void cancel(UUID orderId, String symbol) {
        send(Topics.ORDERS_SUBMITTED.name(), symbol, EventEnvelope.of(EventTypes.CANCEL_REQUESTED, 1, symbol,
                new CancelRequested(orderId, account, symbol, Instant.now()), Clock.systemUTC()));
    }

    private void send(String topic, String key, EventEnvelope<?> event) {
        try {
            producer.send(new ProducerRecord<>(topic, key, EventJson.write(event))).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<EventEnvelope<JsonNode>> eventsFor(UUID orderId, int atLeast) {
        await().atMost(Duration.ofSeconds(30)).until(() -> received.getOrDefault(orderId.toString(), List.of()).size() >= atLeast);
        List<EventEnvelope<JsonNode>> events = new ArrayList<>();
        for (ConsumerRecord<String, String> r : received.get(orderId.toString())) {
            assertThat(r.key()).isEqualTo(account.toString());     // executions are keyed by account
            events.add(EventJson.readRaw(r.value()));
        }
        return events;
    }

    private void assertNoMoreEventsFor(UUID orderId, int expected) throws InterruptedException {
        Thread.sleep(1_000);
        assertThat(received.getOrDefault(orderId.toString(), List.of())).hasSize(expected);
    }

    private static String text(EventEnvelope<JsonNode> e, String field) {
        return e.payload().get(field).asText();
    }

    // ---- tests -----------------------------------------------------------------------------------------------

    @Test
    void marketBuyFillsDisplayedSizeNowAndTheRestOnTheNextTick() {
        quote("AAPL", "199.99", 1000, "200.01", 300);
        UUID order = accept("AAPL", OrderSide.BUY, OrderType.MARKET, TimeInForce.DAY, "500", null, "210.01");

        EventEnvelope<JsonNode> first = eventsFor(order, 1).getFirst();
        assertThat(first.eventType()).isEqualTo(EventTypes.EXECUTION_REPORTED);
        assertThat(text(first, "qty")).isEqualTo("300.000000");
        assertThat(text(first, "price")).isEqualTo("200.0100");
        assertThat(first.payload().get("terminal").asBoolean()).isFalse();

        tick("AAPL", "200.03", 1000, "200.05", 1000);

        EventEnvelope<JsonNode> second = eventsFor(order, 2).get(1);
        assertThat(text(second, "qty")).isEqualTo("200.000000");
        assertThat(text(second, "price")).isEqualTo("200.0500");
        assertThat(second.payload().get("seq").asInt()).isEqualTo(2);
        assertThat(text(second, "remainingQty")).isEqualTo("0.000000");
        assertThat(second.payload().get("terminal").asBoolean()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM executions WHERE order_id = :o").param("o", order)
                .query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void limitBuyBelowMarketRestsThenFillsWhenThePriceCrosses() throws Exception {
        quote("TSLA", "249.99", 1000, "250.01", 1000);
        UUID order = accept("TSLA", OrderSide.BUY, OrderType.LIMIT, TimeInForce.GTC, "10", "245.00", "245.00");

        await().atMost(Duration.ofSeconds(30)).until(() -> jdbc.sql("SELECT count(*) FROM resting_orders WHERE order_id = :o")
                .param("o", order).query(Long.class).single() == 1);
        tick("TSLA", "245.40", 1000, "245.50", 1000);     // still above the limit
        assertNoMoreEventsFor(order, 0);

        tick("TSLA", "244.80", 1000, "244.90", 1000);     // crosses
        EventEnvelope<JsonNode> fill = eventsFor(order, 1).getFirst();
        assertThat(text(fill, "price")).isEqualTo("244.9000");      // filled at the ask, better than the limit
        assertThat(fill.payload().get("terminal").asBoolean()).isTrue();
    }

    @Test
    void marketSellFillsAtTheBid() {
        quote("GME", "24.99", 1000, "25.01", 1000);
        UUID order = accept("GME", OrderSide.SELL, OrderType.MARKET, TimeInForce.DAY, "1.5", null, null);

        EventEnvelope<JsonNode> fill = eventsFor(order, 1).getFirst();
        assertThat(text(fill, "price")).isEqualTo("24.9900");
        assertThat(text(fill, "qty")).isEqualTo("1.500000");
    }

    @Test
    void marketBuyRemainderIsCanceledWhenThePriceLeavesTheCollar() {
        quote("NVDA", "119.99", 1000, "120.01", 100);
        UUID order = accept("NVDA", OrderSide.BUY, OrderType.MARKET, TimeInForce.DAY, "1000", null, "126.01");
        eventsFor(order, 1);

        tick("NVDA", "126.50", 1000, "126.60", 1000);

        EventEnvelope<JsonNode> canceled = eventsFor(order, 2).get(1);
        assertThat(canceled.eventType()).isEqualTo(EventTypes.ORDER_CANCELED);
        assertThat(text(canceled, "reason")).isEqualTo("COLLAR_EXCEEDED");
        assertThat(text(canceled, "canceledQty")).isEqualTo("900.000000");
    }

    @Test
    void cancelRemovesARestingOrderAndLateCancelsAreRejectedWithTheReason() {
        quote("MSFT", "419.99", 1000, "420.01", 1000);
        UUID resting = accept("MSFT", OrderSide.BUY, OrderType.LIMIT, TimeInForce.GTC, "3", "400.00", "400.00");
        cancel(resting, "MSFT");

        EventEnvelope<JsonNode> canceled = eventsFor(resting, 1).getFirst();
        assertThat(canceled.eventType()).isEqualTo(EventTypes.ORDER_CANCELED);
        assertThat(text(canceled, "reason")).isEqualTo("USER_REQUESTED");
        assertThat(text(canceled, "canceledQty")).isEqualTo("3.000000");

        cancel(resting, "MSFT");
        assertThat(text(eventsFor(resting, 2).get(1), "reason")).isEqualTo("ALREADY_CANCELED");

        UUID filled = accept("MSFT", OrderSide.BUY, OrderType.MARKET, TimeInForce.DAY, "1", null, "441.01");
        eventsFor(filled, 1);
        cancel(filled, "MSFT");
        EventEnvelope<JsonNode> late = eventsFor(filled, 2).get(1);
        assertThat(late.eventType()).isEqualTo(EventTypes.CANCEL_REJECTED);
        assertThat(text(late, "reason")).isEqualTo("ALREADY_FILLED");

        UUID unknown = UUID.randomUUID();
        cancel(unknown, "MSFT");
        assertThat(text(eventsFor(unknown, 1).getFirst(), "reason")).isEqualTo("UNKNOWN_ORDER");
    }

    @Test
    void duplicateOrderEventsAreAppliedOnce() throws Exception {
        quote("AMD", "149.99", 1000, "150.01", 1000);
        UUID orderId = UUID.randomUUID();
        EventEnvelope<OrderAccepted> event = acceptedEvent(orderId, "AMD", OrderSide.BUY, OrderType.MARKET,
                TimeInForce.DAY, "2", null, "157.51");

        send(Topics.ORDERS_SUBMITTED.name(), "AMD", event);
        send(Topics.ORDERS_SUBMITTED.name(), "AMD", event);   // redelivery: same eventId

        eventsFor(orderId, 1);
        assertNoMoreEventsFor(orderId, 1);
        assertThat(jdbc.sql("SELECT count(*) FROM executions WHERE order_id = :o").param("o", orderId)
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void dayOrdersExpireAtTheCloseAndGtcOrdersStay() throws Exception {
        quote("INTC", "21.99", 1000, "22.01", 1000);
        UUID day = accept("INTC", OrderSide.BUY, OrderType.LIMIT, TimeInForce.DAY, "5", "20.00", "20.00");
        UUID gtc = accept("INTC", OrderSide.BUY, OrderType.LIMIT, TimeInForce.GTC, "5", "20.00", "20.00");
        await().atMost(Duration.ofSeconds(30)).until(() -> jdbc.sql("SELECT count(*) FROM resting_orders WHERE order_id IN (:a, :b)")
                .param("a", day).param("b", gtc).query(Long.class).single() == 2);

        clock.now.set(Instant.parse("2026-01-05T21:00:01Z"));    // 16:00:01 New York

        EventEnvelope<JsonNode> expired = eventsFor(day, 1).getFirst();
        assertThat(expired.eventType()).isEqualTo(EventTypes.ORDER_EXPIRED);
        assertThat(text(expired, "expiredQty")).isEqualTo("5.000000");
        assertNoMoreEventsFor(gtc, 0);
        assertThat(jdbc.sql("SELECT count(*) FROM resting_orders WHERE order_id = :g").param("g", gtc)
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void marketOrderArrivingAfterTheCloseExpiresImmediately() {
        clock.now.set(Instant.parse("2026-01-05T21:30:00Z"));
        UUID order = accept("CSCO", OrderSide.BUY, OrderType.MARKET, TimeInForce.DAY, "1", null, "60.00");

        assertThat(eventsFor(order, 1).getFirst().eventType()).isEqualTo(EventTypes.ORDER_EXPIRED);
    }

    @Test
    void restingOrdersSurviveARestartOfTheEngine() throws Exception {
        quote("ORCL", "159.99", 1000, "160.01", 1000);
        UUID order = accept("ORCL", OrderSide.SELL, OrderType.LIMIT, TimeInForce.GTC, "4", "165.00", null);
        await().atMost(Duration.ofSeconds(30)).until(() -> jdbc.sql("SELECT count(*) FROM resting_orders WHERE order_id = :o")
                .param("o", order).query(Long.class).single() == 1);

        SmartLifecycle engine = lifecycles.stream()
                .filter(l -> l.getClass().getSimpleName().equals("ExecutionConsumer")).findFirst().orElseThrow();
        engine.stop();
        engine.start();       // in-memory book is rebuilt from resting_orders on partition assignment

        tick("ORCL", "165.10", 1000, "165.20", 1000);
        EventEnvelope<JsonNode> fill = eventsFor(order, 1).getFirst();
        assertThat(text(fill, "price")).isEqualTo("165.1000");
        assertThat(fill.payload().get("terminal").asBoolean()).isTrue();
    }
}
