package com.tradewind.portfolio.ledger;

import com.tradewind.portfolio.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real Postgres, real HTTP: funding, reservations, concurrency and the database's own ledger guards. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
class LedgerIntegrationTest {

    @Autowired
    TestRestTemplate http;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    TransactionTemplate transactions;

    // ---- helpers ---------------------------------------------------------------------------------------------

    private ResponseEntity<Map> deposit(UUID reference, UUID account, long cents) {
        return http.postForEntity("/internal/ledger/deposits",
                Map.of("referenceId", reference, "accountId", account, "amountCents", cents), Map.class);
    }

    private ResponseEntity<Map> withdraw(UUID reference, UUID account, long cents) {
        return http.postForEntity("/internal/ledger/withdrawals",
                Map.of("referenceId", reference, "accountId", account, "amountCents", cents), Map.class);
    }

    private ResponseEntity<Map> reserveCash(UUID order, UUID account, long cents) {
        return http.postForEntity("/internal/reservations",
                Map.of("orderId", order, "accountId", account, "cashCents", cents), Map.class);
    }

    private ResponseEntity<Map> reserveShares(UUID order, UUID account, String symbol, String quantity) {
        return http.postForEntity("/internal/reservations",
                Map.of("orderId", order, "accountId", account, "symbol", symbol, "quantity", quantity), Map.class);
    }

    private ResponseEntity<Map> release(UUID order) {
        return http.postForEntity("/internal/reservations/" + order + "/release", null, Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> balances(UUID account) {
        return http.getForObject("/internal/accounts/" + account + "/balances", Map.class);
    }

    private long cash(UUID account) {
        return ((Number) balances(account).get("cashCents")).longValue();
    }

    private long reservedCash(UUID account) {
        return ((Number) balances(account).get("cashReservedCents")).longValue();
    }

    private UUID fundedAccount(long cents) {
        UUID account = UUID.randomUUID();
        assertThat(deposit(UUID.randomUUID(), account, cents).getStatusCode()).isEqualTo(HttpStatus.OK);
        return account;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> reconcile() {
        return http.getForObject("/internal/reconciliation", Map.class);
    }

    // ---- funding ---------------------------------------------------------------------------------------------

    @Test
    void depositIsIdempotentPerReference() {
        UUID account = UUID.randomUUID();
        UUID reference = UUID.randomUUID();

        ResponseEntity<Map> first = deposit(reference, account, 10_000_000);
        ResponseEntity<Map> replay = deposit(reference, account, 10_000_000);

        assertThat(first.getBody()).containsEntry("cashBalanceCents", 10_000_000);
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        assertThat(cash(account)).isEqualTo(10_000_000);
        assertThat(deposit(reference, account, 5).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void withdrawalCannotOverdrawAndLeavesNoTrace() {
        UUID account = fundedAccount(5_000);

        ResponseEntity<Map> tooMuch = withdraw(UUID.randomUUID(), account, 5_001);
        ResponseEntity<Map> ok = withdraw(UUID.randomUUID(), account, 5_000);

        assertThat(tooMuch.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(tooMuch.getBody()).containsEntry("code", "INSUFFICIENT_BUYING_POWER");
        assertThat(ok.getBody()).containsEntry("cashBalanceCents", 0);
    }

    // ---- reservations ----------------------------------------------------------------------------------------

    @Test
    void reserveMovesCashToReservedAndReleaseMovesItBack() {
        UUID account = fundedAccount(100_000);
        UUID order = UUID.randomUUID();

        ResponseEntity<Map> reserved = reserveCash(order, account, 30_000);
        assertThat(reserved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reserved.getBody()).containsEntry("status", "OPEN").containsEntry("asset", "USD")
                .containsEntry("reserved", "300").containsEntry("remaining", "300");
        assertThat(cash(account)).isEqualTo(70_000);
        assertThat(reservedCash(account)).isEqualTo(30_000);

        assertThat(release(order).getBody()).containsEntry("status", "CLOSED");
        assertThat(cash(account)).isEqualTo(100_000);
        assertThat(reservedCash(account)).isZero();
    }

    @Test
    void reservedMoneyCannotBeWithdrawnOrReservedTwice() {
        UUID account = fundedAccount(10_000);
        reserveCash(UUID.randomUUID(), account, 8_000);

        assertThat(withdraw(UUID.randomUUID(), account, 5_000).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        ResponseEntity<Map> second = reserveCash(UUID.randomUUID(), account, 5_000);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(second.getBody()).containsEntry("code", "INSUFFICIENT_BUYING_POWER");
        assertThat(cash(account)).isEqualTo(2_000);
    }

    @Test
    void reserveAndReleaseAreIdempotentPerOrder() {
        UUID account = fundedAccount(10_000);
        UUID order = UUID.randomUUID();

        reserveCash(order, account, 4_000);
        assertThat(reserveCash(order, account, 4_000).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cash(account)).isEqualTo(6_000);
        assertThat(reserveCash(order, account, 4_001).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        release(order);
        release(order);
        assertThat(cash(account)).isEqualTo(10_000);
        assertThat(ledgerTxnCount("reserve:" + order)).isEqualTo(1);
        assertThat(ledgerTxnCount("release:" + order)).isEqualTo(1);
    }

    @Test
    void releaseBeforeReserveBlocksTheLateReserve() {
        UUID account = fundedAccount(10_000);
        UUID order = UUID.randomUUID();

        assertThat(release(order).getBody()).containsEntry("status", "CLOSED");
        assertThat(reserveCash(order, account, 1_000).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(cash(account)).isEqualTo(10_000);
    }

    @Test
    void sharesCannotBeReservedWithoutAPosition() {
        ResponseEntity<Map> response = reserveShares(UUID.randomUUID(), UUID.randomUUID(), "AAPL", "1.5");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).containsEntry("code", "INSUFFICIENT_SHARES");
    }

    @Test
    void invalidReserveRequestsAreRejected() {
        UUID account = UUID.randomUUID();
        assertThat(http.postForEntity("/internal/reservations",
                Map.of("orderId", UUID.randomUUID(), "accountId", account), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.postForEntity("/internal/reservations", Map.of("orderId", UUID.randomUUID(),
                        "accountId", account, "cashCents", 100, "symbol", "AAPL", "quantity", "1"), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(reserveShares(UUID.randomUUID(), account, "AAPL", "0.0000001").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.postForEntity("/internal/reservations", Map.of("orderId", UUID.randomUUID(),
                "accountId", account, "cashCents", 100.5), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---- concurrency -----------------------------------------------------------------------------------------

    @Test
    void concurrentReservationsNeverSpendTheSameMoneyTwice() throws Exception {
        UUID account = fundedAccount(100_000);  // room for exactly 10 reservations of $100

        List<HttpStatus> results = concurrently(20, () -> status(reserveCash(UUID.randomUUID(), account, 10_000)));

        assertThat(results).filteredOn(s -> s == HttpStatus.OK).hasSize(10);
        assertThat(results).filteredOn(s -> s == HttpStatus.UNPROCESSABLE_ENTITY).hasSize(10);
        assertThat(cash(account)).isZero();
        assertThat(reservedCash(account)).isEqualTo(100_000);
    }

    @Test
    void concurrentDepositsAllLandDespiteContention() throws Exception {
        UUID account = UUID.randomUUID();

        List<HttpStatus> results = concurrently(40, () -> status(deposit(UUID.randomUUID(), account, 100)));

        assertThat(results).containsOnly(HttpStatus.OK);
        assertThat(cash(account)).isEqualTo(4_000);
    }

    // ---- invariants ------------------------------------------------------------------------------------------

    @Test
    void everyTransactionSumsToZeroPerUnitAndReconciliationIsExact() throws Exception {
        UUID account = fundedAccount(50_000);
        concurrently(20, () -> {
            UUID order = UUID.randomUUID();
            reserveCash(order, account, 3_000);
            release(order);
            deposit(UUID.randomUUID(), account, 1);
            return HttpStatus.OK;
        });

        long unbalanced = jdbc.sql("""
                        SELECT count(*) FROM (
                            SELECT e.txn_id FROM ledger_entries e JOIN ledger_accounts a ON a.id = e.ledger_account_id
                            GROUP BY e.txn_id, coalesce(a.symbol, 'USD') HAVING sum(e.amount) <> 0) t""")
                .query(Long.class).single();
        assertThat(unbalanced).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM ledger_txns").query(Long.class).single()).isPositive();
        assertThat(reconcile()).containsEntry("ok", true);
        assertThat(cash(account)).isEqualTo(50_020);
    }

    @Test
    void databaseRejectsUnbalancedTransactionsEvenIfCodeIsBypassed() {
        UUID account = fundedAccount(1_000);
        UUID cashAccount = jdbc.sql("SELECT id FROM ledger_accounts WHERE account_id = :a AND type = 'CASH'")
                .param("a", account).query(UUID.class).single();

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            UUID txn = UUID.randomUUID();
            jdbc.sql("INSERT INTO ledger_txns VALUES (:id, 'DEPOSIT', :ref, now())")
                    .param("id", txn).param("ref", "rogue:" + txn).update();
            jdbc.sql("INSERT INTO ledger_entries VALUES (:id, :txn, :acct, 1000000, now())")
                    .param("id", UUID.randomUUID()).param("txn", txn).param("acct", cashAccount).update();
        })).rootCause().hasMessageContaining("does not balance");

        assertThatThrownBy(() -> jdbc.sql("UPDATE ledger_entries SET amount = amount * 2").update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("UPDATE balances SET amount = -1 WHERE ledger_account_id = :id")
                .param("id", cashAccount).update())
                .hasMessageContaining("balances_non_negative");
    }

    @Test
    void reconciliationDetectsATamperedCachedBalance() {
        UUID account = fundedAccount(1_000);
        UUID cashAccount = jdbc.sql("SELECT id FROM ledger_accounts WHERE account_id = :a AND type = 'CASH'")
                .param("a", account).query(UUID.class).single();
        try {
            jdbc.sql("UPDATE balances SET amount = amount + 1 WHERE ledger_account_id = :id").param("id", cashAccount).update();

            Map<String, Object> report = reconcile();

            assertThat(report).containsEntry("ok", false);
            assertThat((List<?>) report.get("balanceMismatches")).hasSize(1);
        } finally {
            jdbc.sql("UPDATE balances SET amount = amount - 1 WHERE ledger_account_id = :id").param("id", cashAccount).update();
        }
        assertThat(reconcile()).containsEntry("ok", true);
    }

    private long ledgerTxnCount(String reference) {
        return jdbc.sql("SELECT count(*) FROM ledger_txns WHERE reference_id = :r").param("r", reference)
                .query(Long.class).single();
    }

    private static HttpStatus status(ResponseEntity<?> response) {
        return HttpStatus.valueOf(response.getStatusCode().value());
    }

    private static <T> List<T> concurrently(int threads, Callable<T> task) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        }
    }

    @SuppressWarnings("unused")
    private static BigDecimal dollars(String value) {
        return new BigDecimal(value);
    }
}
