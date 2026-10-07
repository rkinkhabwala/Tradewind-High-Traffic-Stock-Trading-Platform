package com.tradewind.correctness;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Phase 1 acceptance: "Same Idempotency-Key sent 5 times concurrently -> exactly one order." The five requests are
 * spread over two order-service instances, and the effect is checked in every service, not just order-service.
 */
class IdempotencyAcrossServicesTest {

    private final Cluster cluster = Cluster.get();

    @Test
    void fiveConcurrentRequestsWithOneKeyProduceExactlyOneOrderReservationAndFill() throws Exception {
        UUID account = UUID.randomUUID();
        cluster.api.deposit(account, 10_000_000);
        cluster.publishQuote("IDEM", "49.99", "50.01", 10_000);
        Map<String, Object> order = Map.of("symbol", "IDEM", "side", "BUY", "type", "MARKET", "qty", "10");

        CountDownLatch start = new CountDownLatch(1);
        List<Future<Api.Response>> responses = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(5)) {
            for (int i = 0; i < 5; i++) {
                int instance = i;
                responses.add(pool.submit(() -> {
                    start.await();
                    return cluster.api.placeOrder(instance, account, "same-key", order);
                }));
            }
            start.countDown();
            List<String> ids = new ArrayList<>();
            for (Future<Api.Response> response : responses) {
                assertThat(response.get().status()).isEqualTo(202);
                ids.add(response.get().text("id"));
            }
            assertThat(ids).containsOnly(ids.getFirst());

            UUID orderId = UUID.fromString(ids.getFirst());
            assertThat(cluster.ordersDb.sql("SELECT count(*) FROM orders WHERE account_id = :a")
                    .param("a", account).query(Long.class).single()).isEqualTo(1);
            assertThat(cluster.portfolioDb.sql("SELECT count(*) FROM reservations WHERE account_id = :a")
                    .param("a", account).query(Long.class).single()).isEqualTo(1);
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                    assertThat(cluster.api.order(account, orderId).text("status")).isEqualTo("FILLED"));
            assertThat(cluster.executionDb.sql("SELECT count(*) FROM executions WHERE order_id = :o")
                    .param("o", orderId).query(Long.class).single()).isEqualTo(1);
            assertThat(((Number) cluster.api.balances(account).body().get("cashCents")).longValue())
                    .isEqualTo(10_000_000 - 50_010);
        }
    }
}
