package com.tradewind.order;

import com.tradewind.common.event.EventEnvelope;
import com.tradewind.common.event.EventJson;
import com.tradewind.common.event.EventTypes;
import com.tradewind.common.kafka.KafkaDefaults;
import com.tradewind.common.money.Price;
import com.tradewind.common.order.OrderAccepted;
import com.tradewind.common.quote.QuoteHash;
import com.tradewind.common.quote.QuoteTick;
import com.tradewind.order.order.PendingOrderSweeper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestInfrastructure.class)
@TestPropertySource(properties = {
        "tradewind.orders.pending-timeout=1s",
        "tradewind.orders.sweep-interval=1h"     // the sweeper is driven explicitly by the tests
})
class OrderFlowIntegrationTest {

    @Autowired
    TestRestTemplate http;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    FakeReservations reservations;
    @Autowired
    TestInfrastructure.TestClock clock;
    @Autowired
    PendingOrderSweeper sweeper;
    @Autowired
    KafkaContainer kafka;

    private UUID account;

    @BeforeEach
    void marketIsOpenWithQuotes() {
        reservations.reset();
        clock.now.set(TestInfrastructure.MONDAY_10AM);
        account = UUID.randomUUID();
        redis.opsForSet().add(QuoteHash.SYMBOLS_KEY, "AAPL", "TSLA", "GME");
        quote("AAPL", "199.99", "200.01", "200.00");
        quote("TSLA", "249.98", "250.02", "250.00");
    }

    private void quote(String symbol, String bid, String ask, String last) {
        QuoteTick tick = new QuoteTick(symbol, Price.of(bid), Price.of(ask), Price.of(last), 500, 500, 0,
                TestInfrastructure.MONDAY_10AM);
        redis.opsForHash().putAll(QuoteHash.key(symbol), QuoteHash.toHash(tick, Price.of(last)));
    }

    private HttpHeaders headers(UUID accountId, String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (accountId != null) {
            headers.set("X-Account-Id", accountId.toString());
        }
        if (key != null) {
            headers.set("Idempotency-Key", key);
        }
        return headers;
    }

    private ResponseEntity<Map> place(String key, Map<String, Object> body) {
        return http.postForEntity("/api/orders", new HttpEntity<>(body, headers(account, key)), Map.class);
    }

    private static Map<String, Object> marketBuy(String symbol, String qty) {
        return Map.of("symbol", symbol, "side", "BUY", "type", "MARKET", "qty", qty);
    }

    private static Map<String, Object> limit(String side, String qty, String price, String tif) {
        Map<String, Object> body = new HashMap<>(Map.of("symbol", "AAPL", "side", side, "type", "LIMIT",
                "qty", qty, "limitPrice", price));
        if (tif != null) {
            body.put("timeInForce", tif);
        }
        return body;
    }

    private ResponseEntity<Map> cancel(UUID orderId, String key) {
        return http.exchange("/api/orders/" + orderId, HttpMethod.DELETE, new HttpEntity<>(headers(account, key)), Map.class);
    }

    private long ordersFor(String key) {
        return jdbc.sql("SELECT count(*) FROM orders WHERE account_id = :a AND client_order_id = :k")
                .param("a", account).param("k", key).query(Long.class).single();
    }

    private List<EventEnvelope<Map>> eventsFor(UUID orderId, int expected) {
        List<EventEnvelope<Map>> events = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(
                KafkaDefaults.consumer(kafka.getBootstrapServers(), "test-" + UUID.randomUUID()))) {
            consumer.subscribe(List.of("orders.submitted"));
            await().atMost(Duration.ofSeconds(15)).until(() -> {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(200))) {
                    EventEnvelope<Map> e = EventJson.read(r.value(), Map.class);
                    if (orderId.toString().equals(e.payload().get("orderId"))) {
                        assertThat(r.key()).isEqualTo(e.key());
                        events.add(e);
                    }
                }
                return events.size() >= expected;
            });
            consumer.poll(Duration.ofMillis(500)).forEach(r -> {
                EventEnvelope<Map> e = EventJson.read(r.value(), Map.class);
                if (orderId.toString().equals(e.payload().get("orderId"))) {
                    events.add(e);
                }
            });
        }
        return events;
    }

    // ---- placement -------------------------------------------------------------------------------------------

    @Test
    void marketBuyIsReservedAcceptedAndPublishedThroughTheOutbox() {
        ResponseEntity<Map> response = place("buy-1", marketBuy("AAPL", "10"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).containsEntry("status", "ACCEPTED").containsEntry("symbol", "AAPL")
                .containsEntry("qty", "10.000000").containsEntry("clientOrderId", "buy-1");
        UUID orderId = UUID.fromString((String) response.getBody().get("id"));
        assertThat(reservations.reservedCash).containsEntry(orderId, 210_011L);   // 10 x 200.01 x 1.05, rounded up

        List<EventEnvelope<Map>> events = eventsFor(orderId, 1);
        assertThat(events).hasSize(1);
        EventEnvelope<Map> event = events.getFirst();
        assertThat(event.eventType()).isEqualTo(EventTypes.ORDER_ACCEPTED);
        assertThat(event.key()).isEqualTo("AAPL");
        OrderAccepted accepted = EventJson.convert(EventJson.readRaw(EventJson.write(event)).payload(), OrderAccepted.class);
        assertThat(accepted.priceCap()).isEqualTo(Price.of("210.01"));
    }

    @Test
    void sameKeyFiveTimesConcurrentlyCreatesExactlyOneOrder() throws Exception {
        reservations.delay = Duration.ofMillis(200);
        int before = reservations.reserveCalls.get();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResponseEntity<Map>>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(5)) {
            for (int i = 0; i < 5; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return place("same-key", marketBuy("AAPL", "1"));
                }));
            }
            start.countDown();
            Object firstId = results.getFirst().get().getBody().get("id");
            for (Future<ResponseEntity<Map>> result : results) {
                assertThat(result.get().getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
                assertThat(result.get().getBody().get("id")).isEqualTo(firstId);
            }
            assertThat(ordersFor("same-key")).isEqualTo(1);
            assertThat(reservations.reserveCalls.get() - before).isEqualTo(1);
            assertThat(eventsFor(UUID.fromString((String) firstId), 1)).hasSize(1);
        }
    }

    @Test
    void retryWithSameKeyReplaysAndDifferentBodyIsRejected() {
        ResponseEntity<Map> first = place("retry-1", marketBuy("AAPL", "2"));
        ResponseEntity<Map> replay = place("retry-1", marketBuy("AAPL", "2"));
        ResponseEntity<Map> different = place("retry-1", marketBuy("AAPL", "3"));

        assertThat(replay.getBody()).isEqualTo(first.getBody());
        assertThat(different.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(ordersFor("retry-1")).isEqualTo(1);
        assertThat(redis.hasKey("idem:" + account + ":retry-1")).isTrue();
    }

    @Test
    void insufficientBuyingPowerRejectsTheOrderWithItsId() {
        reservations.mode = FakeReservations.Mode.INSUFFICIENT;

        ResponseEntity<Map> response = place("poor-1", marketBuy("AAPL", "1000000"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody()).containsEntry("code", "INSUFFICIENT_BUYING_POWER").containsKey("orderId");
        assertThat(jdbc.sql("SELECT status FROM orders WHERE client_order_id = 'poor-1' AND account_id = :a")
                .param("a", account).query(String.class).single()).isEqualTo("REJECTED");
    }

    @Test
    void validationFailuresCreateNoOrder() {
        assertThat(place("v1", marketBuy("NOPE", "1")).getBody()).containsEntry("code", "UNKNOWN_SYMBOL");
        assertThat(place("v2", limit("BUY", "1", "350.00", null)).getBody()).containsEntry("code", "PRICE_OUT_OF_BAND");
        assertThat(place("v3", limit("BUY", "1", "190.001", null)).getBody()).containsEntry("code", "INVALID_TICK");
        assertThat(place("v4", marketBuy("AAPL", "0")).getBody()).containsEntry("code", "INVALID_QUANTITY");
        clock.now.set(Instant.parse("2026-01-10T15:00:00Z"));   // Saturday
        assertThat(place("v5", marketBuy("AAPL", "1")).getBody()).containsEntry("code", "MARKET_CLOSED");
        assertThat(place("v6", limit("BUY", "1", "190.00", "DAY")).getBody()).containsEntry("code", "MARKET_CLOSED");
        assertThat(place("v7", limit("BUY", "1", "190.00", "GTC")).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        for (String key : List.of("v1", "v2", "v3", "v4", "v5", "v6")) {
            assertThat(ordersFor(key)).as(key).isZero();
        }
    }

    @Test
    void requestsWithoutKeyOrIdentityOrWithMalformedBodiesAreRejected() {
        assertThat(place(null, marketBuy("AAPL", "1")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.postForEntity("/api/orders", new HttpEntity<>(marketBuy("AAPL", "1"), headers(null, "k")),
                Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(place("bad-side", Map.of("symbol", "AAPL", "side", "HOLD", "type", "MARKET", "qty", "1"))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(place("bad-qty", marketBuy("AAPL", "0.0000001")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---- unknown reservation outcomes --------------------------------------------------------------------------

    @Test
    void portfolioOutageLeavesOrderPendingAndARetryResumesTheSameOrder() {
        reservations.mode = FakeReservations.Mode.UNAVAILABLE;
        ResponseEntity<Map> failed = place("outage-1", marketBuy("TSLA", "1"));
        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(failed.getHeaders().getFirst("Retry-After")).isEqualTo("1");
        String pendingId = jdbc.sql("SELECT id FROM orders WHERE client_order_id = 'outage-1' AND account_id = :a AND status = 'PENDING'")
                .param("a", account).query(String.class).single();

        reservations.mode = FakeReservations.Mode.RESERVE;
        ResponseEntity<Map> retried = place("outage-1", marketBuy("TSLA", "1"));

        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(retried.getBody().get("id")).isEqualTo(pendingId);
        assertThat(ordersFor("outage-1")).isEqualTo(1);
    }

    @Test
    void sweeperRejectsStuckPendingOrdersAndReleasesTheirReservation() throws Exception {
        reservations.mode = FakeReservations.Mode.UNAVAILABLE;
        place("stuck-1", marketBuy("AAPL", "1"));
        UUID id = UUID.fromString(jdbc.sql("SELECT id FROM orders WHERE client_order_id = 'stuck-1' AND account_id = :a")
                .param("a", account).query(String.class).single());
        Thread.sleep(1_200);   // older than the 1s pending timeout

        sweeper.sweep();        // portfolio still down: order rejected, release queued
        assertThat(jdbc.sql("SELECT status || ':' || reject_reason || ':' || release_pending FROM orders WHERE id = :id")
                .param("id", id).query(String.class).single()).isEqualTo("REJECTED:RESERVATION_TIMEOUT:true");
        assertThat(reservations.released).doesNotContain(id);

        reservations.mode = FakeReservations.Mode.RESERVE;
        sweeper.sweep();        // portfolio back: release goes through exactly once
        assertThat(reservations.released).containsOnlyOnce(id);

        // A late retry of the same request now sees the rejection, not a second order.
        ResponseEntity<Map> late = place("stuck-1", marketBuy("AAPL", "1"));
        assertThat(late.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(late.getBody()).containsEntry("code", "RESERVATION_TIMEOUT");
    }

    // ---- cancel ----------------------------------------------------------------------------------------------

    @Test
    void cancelOfAnOpenOrderEmitsOneCancelRequestedAfterTheAcceptance() {
        UUID orderId = UUID.fromString((String) place("to-cancel", limit("BUY", "1", "190.00", "GTC")).getBody().get("id"));

        ResponseEntity<Map> first = cancel(orderId, "cancel-1");
        ResponseEntity<Map> second = cancel(orderId, "cancel-2");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(first.getBody()).containsEntry("status", "ACCEPTED").containsKey("cancelRequestedAt");
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        List<EventEnvelope<Map>> events = eventsFor(orderId, 2);
        assertThat(events).extracting(EventEnvelope::eventType)
                .containsExactly(EventTypes.ORDER_ACCEPTED, EventTypes.CANCEL_REQUESTED);
    }

    @Test
    void cancelRules() {
        UUID other = UUID.fromString((String) place("mine", limit("BUY", "1", "190.00", null)).getBody().get("id"));
        UUID myAccount = account;
        account = UUID.randomUUID();
        assertThat(cancel(other, "steal").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        account = myAccount;

        reservations.mode = FakeReservations.Mode.INSUFFICIENT;
        UUID rejected = UUID.fromString((String) place("rej", limit("BUY", "1", "190.00", null)).getBody().get("orderId"));
        assertThat(cancel(rejected, "c-rej").getBody()).containsEntry("code", "ORDER_NOT_OPEN");

        reservations.mode = FakeReservations.Mode.UNAVAILABLE;
        place("pend", limit("BUY", "1", "190.00", null));
        UUID pending = UUID.fromString(jdbc.sql("SELECT id FROM orders WHERE client_order_id = 'pend' AND account_id = :a")
                .param("a", account).query(String.class).single());
        ResponseEntity<Map> notYet = cancel(pending, "c-pend");
        assertThat(notYet.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(notYet.getHeaders().getFirst("Retry-After")).isEqualTo("1");
    }

    // ---- reads -----------------------------------------------------------------------------------------------

    @Test
    void getAndCursorPaginatedListAreScopedToTheAccount() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add((String) place("page-" + i, limit("BUY", "1", "190.00", null)).getBody().get("id"));
        }
        HttpEntity<Void> mine = new HttpEntity<>(headers(account, null));

        assertThat(http.exchange("/api/orders/" + ids.getFirst(), HttpMethod.GET, mine, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(http.exchange("/api/orders/" + ids.getFirst(), HttpMethod.GET,
                new HttpEntity<>(headers(UUID.randomUUID(), null)), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        List<Object> seen = new ArrayList<>();
        String cursor = null;
        do {
            String url = "/api/orders?limit=2" + (cursor == null ? "" : "&cursor=" + cursor);
            Map<?, ?> page = http.exchange(url, HttpMethod.GET, mine, Map.class).getBody();
            ((List<?>) page.get("items")).forEach(item -> seen.add(((Map<?, ?>) item).get("id")));
            cursor = (String) page.get("nextCursor");
        } while (cursor != null);

        assertThat(seen).hasSize(5).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(ids);
        assertThat(seen.getFirst()).isEqualTo(ids.getLast());   // newest first
    }
}
