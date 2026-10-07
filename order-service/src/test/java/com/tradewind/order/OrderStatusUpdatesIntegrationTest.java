package com.tradewind.order;

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
import com.tradewind.common.quote.QuoteHash;
import com.tradewind.common.quote.QuoteTick;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** order-service following execution events on Kafka: filled quantity, average price, terminal status. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestInfrastructure.class)
class OrderStatusUpdatesIntegrationTest {

    @Autowired
    TestRestTemplate http;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    KafkaContainer kafka;
    @Autowired
    FakeReservations reservations;
    @Autowired
    TestInfrastructure.TestClock clock;

    private KafkaProducer<String, String> producer;
    private UUID account;

    @BeforeEach
    void setUp() {
        reservations.reset();
        clock.now.set(TestInfrastructure.MONDAY_10AM);
        if (producer == null) {
            producer = new KafkaProducer<>(KafkaDefaults.producer(kafka.getBootstrapServers()));
        }
        account = UUID.randomUUID();
        redis.opsForSet().add(QuoteHash.SYMBOLS_KEY, "AAPL");
        QuoteTick tick = new QuoteTick("AAPL", Price.of("199.99"), Price.of("200.01"), Price.of("200.00"), 500, 500, 0,
                TestInfrastructure.MONDAY_10AM);
        redis.opsForHash().putAll(QuoteHash.key("AAPL"), QuoteHash.toHash(tick, Price.of("200.00")));
    }

    private UUID placeLimitBuy(String key, String qty) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Account-Id", account.toString());
        headers.set("Idempotency-Key", key);
        Map<?, ?> body = http.postForObject("/api/orders", new HttpEntity<>(Map.of("symbol", "AAPL", "side", "BUY",
                "type", "LIMIT", "qty", qty, "limitPrice", "200.00", "timeInForce", "GTC"), headers), Map.class);
        return UUID.fromString((String) body.get("id"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> order(UUID id) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Account-Id", account.toString());
        return http.exchange("/api/orders/" + id, HttpMethod.GET, new HttpEntity<>(headers), Map.class).getBody();
    }

    private EventEnvelope<ExecutionReported> fill(UUID order, int seq, String qty, String price, String cumulative,
                                                  String remaining) {
        return EventEnvelope.of(EventTypes.EXECUTION_REPORTED, 1, account.toString(), new ExecutionReported(
                UUID.randomUUID(), order, account, "AAPL", OrderSide.BUY, seq, Quantity.of(qty), Price.of(price),
                Quantity.of(cumulative), Quantity.of(remaining), Quantity.of(remaining).isZero(), Instant.now()),
                Clock.systemUTC());
    }

    private void send(EventEnvelope<?> event) {
        try {
            producer.send(new ProducerRecord<>(Topics.EXECUTIONS.name(), account.toString(), EventJson.write(event))).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void partialFillsThenFinalFillReachFilledWithTheExactAveragePrice() {
        UUID id = placeLimitBuy("fill-1", "10");

        send(fill(id, 1, "3", "199.97", "3", "7"));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(order(id)).containsEntry("status", "PARTIALLY_FILLED").containsEntry("filledQty", "3.000000");
            assertThat(order(id)).containsEntry("avgFillPrice", "199.9700");
        });

        EventEnvelope<ExecutionReported> last = fill(id, 2, "7", "199.98", "10", "0");
        send(last);
        send(last);     // redelivery is ignored

        // (3 x 199.97 + 7 x 199.98) / 10 = 199.977
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(order(id)).containsEntry("status", "FILLED").containsEntry("filledQty", "10.000000")
                        .containsEntry("avgFillPrice", "199.9770"));
    }

    @Test
    void cancelAfterAPartialFillEndsCanceledWithTheFilledPartKept() {
        UUID id = placeLimitBuy("cancel-1", "10");

        send(fill(id, 1, "4", "199.95", "4", "6"));
        send(EventEnvelope.of(EventTypes.ORDER_CANCELED, 1, account.toString(), new OrderCanceled(id, account, "AAPL",
                OrderSide.BUY, Quantity.of("6"), Quantity.of("4"), "USER_REQUESTED", Instant.now()), Clock.systemUTC()));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(order(id)).containsEntry("status", "CANCELED").containsEntry("filledQty", "4.000000"));
    }

    @Test
    void collarCancelKeepsItsReasonAndExpiryEndsExpired() {
        UUID collared = placeLimitBuy("collar-1", "5");
        UUID expiring = placeLimitBuy("expire-1", "5");

        send(EventEnvelope.of(EventTypes.ORDER_CANCELED, 1, account.toString(), new OrderCanceled(collared, account,
                "AAPL", OrderSide.BUY, Quantity.of("5"), Quantity.ZERO, "COLLAR_EXCEEDED", Instant.now()), Clock.systemUTC()));
        send(EventEnvelope.of(EventTypes.ORDER_EXPIRED, 1, account.toString(), new OrderExpired(expiring, account,
                "AAPL", OrderSide.BUY, Quantity.of("5"), Quantity.ZERO, LocalDate.now(), Instant.now()), Clock.systemUTC()));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(order(collared)).containsEntry("status", "CANCELED").containsEntry("rejectReason", "COLLAR_EXCEEDED");
            assertThat(order(expiring)).containsEntry("status", "EXPIRED");
        });
    }
}
