package com.tradewind.correctness;

import com.tradewind.common.money.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Phase 1 acceptance: "Kill execution-service mid-stream, restart -> no lost or duplicated executions;
 * reconciliation exact." Orders and price ticks keep flowing while execution-service is SIGKILLed and while it is
 * down; after the restart every order must be accounted for exactly once in every service.
 */
class ExecutionCrashRecoveryTest {

    static final int SYMBOLS = 10;
    static final long DEPOSIT_CENTS = 5_000_000;   // $50,000 per account

    record Placed(UUID account, UUID orderId, String symbol) {
    }

    private final Cluster cluster = Cluster.get();
    private final Books books = new Books(cluster);

    @Test
    void killingExecutionMidStreamLosesAndDuplicatesNothing() throws Exception {
        List<UUID> accounts = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            UUID account = UUID.randomUUID();
            cluster.api.deposit(account, DEPOSIT_CENTS);
            accounts.add(account);
        }
        for (int s = 0; s < SYMBOLS; s++) {
            cluster.publishQuote("K" + s, "99.99", "100.01", 1_000);
        }

        // Prices wander across the limit band the whole time, so resting orders keep filling.
        AtomicBoolean ticking = new AtomicBoolean(true);
        Thread ticker = Thread.ofPlatform().start(() -> {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            while (ticking.get()) {
                String symbol = "K" + random.nextInt(SYMBOLS);
                BigDecimal ask = BigDecimal.valueOf(9_800 + random.nextInt(400), 2);
                books.tick(symbol, ask.subtract(new BigDecimal("0.01")).toPlainString(), ask.toPlainString(),
                        1 + random.nextInt(8));
                sleep(5);
            }
        });

        List<Placed> placed = new ArrayList<>();
        AtomicInteger sequence = new AtomicInteger();
        placeOrders(accounts, placed, sequence, 60);
        cluster.killExecution();                         // mid-stream: SIGKILL, no graceful shutdown
        placeOrders(accounts, placed, sequence, 40);     // accepted while execution-service is down
        sleep(2_000);
        cluster.restartExecution();
        placeOrders(accounts, placed, sequence, 40);
        sleep(3_000);
        ticking.set(false);
        ticker.join();

        // Settle: cancel whatever is still open, so every order must end FILLED or CANCELED.
        for (Placed p : placed) {
            String status = cluster.api.order(p.account(), p.orderId()).text("status");
            if (status.equals("ACCEPTED") || status.equals("PARTIALLY_FILLED")) {
                cluster.api.cancel(0, p.account(), "settle-" + p.orderId(), p.orderId());
            }
        }
        await().atMost(Duration.ofMinutes(2)).pollInterval(Duration.ofSeconds(1)).until(() ->
                placed.stream().allMatch(p -> {
                    String s = cluster.api.order(p.account(), p.orderId()).text("status");
                    return s.equals("FILLED") || s.equals("CANCELED");
                }));
        await().atMost(Duration.ofMinutes(1)).pollInterval(Duration.ofSeconds(1)).until(() ->
                cluster.portfolioDb.sql("SELECT count(*) FROM reservations WHERE status = 'OPEN' AND account_id IN (:a)")
                        .param("a", accounts).query(Long.class).single() == 0);

        long executionCount = 0;
        for (Placed p : placed) {
            List<Books.Execution> fills = books.executions(p.orderId());
            executionCount += fills.size();
            // No duplicates and no gaps: seq runs 1..n exactly once (the table also enforces uniqueness).
            for (int i = 0; i < fills.size(); i++) {
                assertThat(fills.get(i).seq()).isEqualTo(i + 1);
            }
            // No lost order: execution-service finished every order that order-service accepted.
            String outcome = cluster.executionDb.sql("SELECT outcome FROM closed_orders WHERE order_id = :o")
                    .param("o", p.orderId()).query(String.class).single();
            Api.Response order = cluster.api.order(p.account(), p.orderId());
            assertThat(outcome).isEqualTo(order.text("status"));
            assertThat(new BigDecimal(order.text("filledQty"))).isEqualByComparingTo(Books.filled(fills));
        }
        // portfolio-service applied each execution exactly once.
        long ledgerFills = cluster.portfolioDb.sql("""
                        SELECT count(*) FROM activity WHERE type = 'FILL' AND account_id IN (:a)""")
                .param("a", accounts).query(Long.class).single();
        assertThat(ledgerFills).isEqualTo(executionCount);
        for (UUID account : accounts) {
            Money cost = Money.ZERO;
            for (Placed p : placed) {
                if (p.account().equals(account)) {
                    cost = cost.plus(Books.cost(books.executions(p.orderId())));
                }
            }
            Map<String, Object> balances = cluster.api.balances(account).body();
            assertThat(((Number) balances.get("cashCents")).longValue()).isEqualTo(DEPOSIT_CENTS - cost.cents());
            assertThat(((Number) balances.get("cashReservedCents")).longValue()).isZero();
        }
        System.out.printf("Crash test: %d orders, %d executions, all accounted for (client retries: %d)%n",
                placed.size(), executionCount, cluster.api.retries.get());
        assertThat(executionCount).isPositive();
        assertThat(cluster.api.reconciliation().body().get("ok")).isEqualTo(true);
    }

    /** Mix of market buys (fill at once) and limit buys inside the tick band (fill later, or partially). */
    private void placeOrders(List<UUID> accounts, List<Placed> placed, AtomicInteger sequence, int count) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < count; i++) {
            int n = sequence.incrementAndGet();
            UUID account = accounts.get(random.nextInt(accounts.size()));
            String symbol = "K" + random.nextInt(SYMBOLS);
            Map<String, Object> order = random.nextInt(4) == 0
                    ? Map.of("symbol", symbol, "side", "BUY", "type", "MARKET", "qty", "3")
                    : Map.of("symbol", symbol, "side", "BUY", "type", "LIMIT", "qty", "5",
                    "limitPrice", BigDecimal.valueOf(9_850 + random.nextInt(300), 2).toPlainString(), "timeInForce", "GTC");
            Api.Response response = cluster.api.placeOrder(n, account, "crash-" + n, order);
            assertThat(response.status()).as(response.toString()).isEqualTo(202);
            placed.add(new Placed(account, UUID.fromString(response.text("id")), symbol));
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
