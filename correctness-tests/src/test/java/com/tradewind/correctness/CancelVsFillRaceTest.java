package com.tradewind.correctness;

import com.tradewind.common.money.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Phase 1 acceptance: "Cancel-vs-fill race (1,000 iterations) always ends in exactly one terminal state with
 * correct balances." Each iteration rests a limit buy, then fires a cancel (through order-service and its outbox)
 * and a crossing tick (straight to Kafka) at the same time, with a random head start for the tick and a random
 * displayed size, so fills, partial fills followed by cancels, and clean cancels all occur.
 */
class CancelVsFillRaceTest {

    static final int ITERATIONS = 1_000;
    static final int PARALLEL = 50;
    static final long DEPOSIT_CENTS = 1_000_000;    // $10,000 per iteration account

    enum Outcome { FILLED, CANCELED_UNFILLED, CANCELED_PARTIAL }

    record Iteration(int index, UUID account, String symbol, UUID orderId) {
    }

    private final Cluster cluster = Cluster.get();
    private final Books books = new Books(cluster);

    @Test
    void everyRaceEndsInExactlyOneTerminalStateWithCorrectBalances() throws Exception {
        List<Iteration> all = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(PARALLEL)) {
            for (int batch = 0; batch < ITERATIONS / PARALLEL; batch++) {
                List<Future<Iteration>> running = new ArrayList<>();
                for (int slot = 0; slot < PARALLEL; slot++) {
                    int index = batch * PARALLEL + slot;
                    String symbol = "R" + slot;     // one symbol per slot: iterations in a batch never share a book
                    running.add(pool.submit(() -> race(index, symbol)));
                }
                for (Future<Iteration> iteration : running) {
                    all.add(iteration.get());
                }
            }
        }

        // Quiesce: every order must reach a terminal state in order-service.
        await().atMost(Duration.ofMinutes(3)).pollInterval(Duration.ofSeconds(1)).until(() ->
                cluster.ordersDb.sql("SELECT count(*) FROM orders WHERE client_order_id LIKE 'race-%' AND status NOT IN ('FILLED', 'CANCELED')")
                        .query(Long.class).single() == 0);
        // ...and portfolio-service must have closed every reservation.
        await().atMost(Duration.ofMinutes(1)).pollInterval(Duration.ofSeconds(1)).until(() ->
                all.stream().allMatch(i -> "CLOSED".equals(reservationStatus(i.orderId()))));

        Map<Outcome, Integer> outcomes = new EnumMap<>(Outcome.class);
        for (Iteration it : all) {
            outcomes.merge(verify(it), 1, Integer::sum);
        }
        System.out.printf("Cancel-vs-fill outcomes over %d iterations: %s (client retries: %d)%n", ITERATIONS, outcomes,
                cluster.api.retries.get());

        assertThat(outcomes.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(ITERATIONS);
        // The race really was a race: both winners occurred.
        assertThat(outcomes.getOrDefault(Outcome.FILLED, 0)).isPositive();
        assertThat(outcomes.getOrDefault(Outcome.CANCELED_UNFILLED, 0) + outcomes.getOrDefault(Outcome.CANCELED_PARTIAL, 0))
                .isPositive();
        assertThat(cluster.api.reconciliation().body().get("ok")).isEqualTo(true);
    }

    private Iteration race(int index, String symbol) throws Exception {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        UUID account = UUID.randomUUID();
        cluster.api.deposit(account, DEPOSIT_CENTS);
        cluster.publishQuote(symbol, "99.99", "100.01", 1_000);

        // 10 shares at 99.00 rests below the 100.01 ask; reserved 990.00.
        Api.Response placed = cluster.api.placeOrder(index, account, "race-" + index,
                Map.of("symbol", symbol, "side", "BUY", "type", "LIMIT", "qty", "10", "limitPrice", "99.00",
                        "timeInForce", "GTC"));
        assertThat(placed.status()).as(placed.toString()).isEqualTo(202);
        UUID orderId = UUID.fromString(placed.text("id"));
        await().atMost(Duration.ofSeconds(30)).until(() -> isResting(orderId));

        // Fire both, the tick with a random head start; displayed size sometimes smaller than the order.
        long size = random.nextBoolean() ? 10 : 1 + random.nextInt(9);
        long tickDelay = random.nextInt(0, 120);
        Thread tick = Thread.ofVirtual().start(() -> {
            sleep(tickDelay);
            books.tick(symbol, "98.49", "98.50", size);
        });
        Api.Response cancel = cluster.api.cancel(index + 1, account, "race-cancel-" + index, orderId);
        assertThat(cancel.status()).as(cancel.toString()).isEqualTo(202);
        tick.join();
        return new Iteration(index, account, symbol, orderId);
    }

    /** Exactly one terminal state, consistent across all three services, and to-the-cent balances. */
    private Outcome verify(Iteration it) {
        Api.Response order = cluster.api.order(it.account(), it.orderId());
        String status = order.text("status");
        List<Books.Execution> fills = books.executions(it.orderId());
        BigDecimal filled = Books.filled(fills);
        String closed = cluster.executionDb.sql("SELECT outcome FROM closed_orders WHERE order_id = :o")
                .param("o", it.orderId()).query(String.class).single();

        assertThat(new BigDecimal(order.text("filledQty"))).as("filled qty, iteration %d", it.index())
                .isEqualByComparingTo(filled);
        assertThat(closed).as("execution outcome, iteration %d", it.index()).isEqualTo(status);
        assertThat(cluster.executionDb.sql("SELECT count(*) FROM resting_orders WHERE order_id = :o")
                .param("o", it.orderId()).query(Long.class).single()).isZero();

        Map<String, Object> balances = cluster.api.balances(it.account()).body();
        Money cost = Books.cost(fills);
        assertThat(((Number) balances.get("cashCents")).longValue()).as("cash, iteration %d", it.index())
                .isEqualTo(DEPOSIT_CENTS - cost.cents());
        assertThat(((Number) balances.get("cashReservedCents")).longValue()).as("reserved, iteration %d", it.index())
                .isZero();
        List<?> positions = (List<?>) balances.get("positions");
        if (filled.signum() == 0) {
            assertThat(positions).isEmpty();
        } else {
            assertThat(new BigDecimal((String) ((Map<?, ?>) positions.getFirst()).get("quantity")))
                    .isEqualByComparingTo(filled);
        }

        if (status.equals("FILLED")) {
            assertThat(filled).isEqualByComparingTo("10");
            return Outcome.FILLED;
        }
        assertThat(status).isEqualTo("CANCELED");
        assertThat(filled).isLessThan(BigDecimal.TEN);
        return filled.signum() == 0 ? Outcome.CANCELED_UNFILLED : Outcome.CANCELED_PARTIAL;
    }

    private boolean isResting(UUID orderId) {
        return cluster.executionDb.sql("SELECT count(*) FROM resting_orders WHERE order_id = :o")
                .param("o", orderId).query(Long.class).single() == 1;
    }

    private String reservationStatus(UUID orderId) {
        return cluster.portfolioDb.sql("SELECT status FROM reservations WHERE order_id = :o")
                .param("o", orderId).query(String.class).optional().orElse("MISSING");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
