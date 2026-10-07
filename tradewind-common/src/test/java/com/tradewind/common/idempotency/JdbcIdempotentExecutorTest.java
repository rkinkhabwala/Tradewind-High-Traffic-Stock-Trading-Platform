package com.tradewind.common.idempotency;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class JdbcIdempotentExecutorTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.15");

    static JdbcClient jdbc;

    @BeforeAll
    static void schema() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("""
                CREATE TABLE idempotency_keys (
                    account_id UUID NOT NULL, key VARCHAR(128) NOT NULL, request_hash CHAR(64) NOT NULL,
                    status_code INT, response_json TEXT,
                    created_at TIMESTAMPTZ NOT NULL, claimed_at TIMESTAMPTZ NOT NULL,
                    PRIMARY KEY (account_id, key))""").update();
    }

    private final JdbcIdempotentExecutor executor = new JdbcIdempotentExecutor(jdbc, Clock.systemUTC());
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

    @Test
    void retryReplaysTheRecordedResponseWithoutRunningAgain() {
        UUID account = UUID.randomUUID();
        AtomicInteger runs = new AtomicInteger();

        StoredResponse first = executor.execute(account, "k1", HASH_A,
                () -> new StoredResponse(201, "{\"run\":" + runs.incrementAndGet() + "}"));
        StoredResponse second = executor.execute(account, "k1", HASH_A,
                () -> new StoredResponse(201, "{\"run\":" + runs.incrementAndGet() + "}"));

        assertThat(second).isEqualTo(first).isEqualTo(new StoredResponse(201, "{\"run\":1}"));
        assertThat(runs).hasValue(1);
    }

    @Test
    void sameKeyIsIndependentPerAccount() {
        AtomicInteger runs = new AtomicInteger();
        executor.execute(UUID.randomUUID(), "shared", HASH_A, () -> new StoredResponse(201, "" + runs.incrementAndGet()));
        executor.execute(UUID.randomUUID(), "shared", HASH_A, () -> new StoredResponse(201, "" + runs.incrementAndGet()));

        assertThat(runs).hasValue(2);
    }

    @Test
    void reusingKeyForDifferentRequestIsRejected() {
        UUID account = UUID.randomUUID();
        executor.execute(account, "k1", HASH_A, () -> new StoredResponse(201, "{}"));

        assertThatThrownBy(() -> executor.execute(account, "k1", HASH_B, () -> new StoredResponse(201, "{}")))
                .isInstanceOf(IdempotencyConflictException.KeyReused.class);
    }

    @Test
    void domainRejectionsAreRecordedButFailuresReleaseTheKey() {
        UUID account = UUID.randomUUID();
        executor.execute(account, "rejected", HASH_A, () -> new StoredResponse(422, "{\"detail\":\"no funds\"}"));
        assertThat(executor.execute(account, "rejected", HASH_A, () -> new StoredResponse(201, "{}")).status())
                .isEqualTo(422);

        assertThatThrownBy(() -> executor.execute(account, "failed", HASH_A, () -> {
            throw new IllegalStateException("downstream timeout");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(executor.execute(account, "failed", HASH_A, () -> new StoredResponse(201, "{}")).status())
                .isEqualTo(201);
    }

    @Test
    void concurrentDuplicatesRunTheOperationExactlyOnce() throws Exception {
        UUID account = UUID.randomUUID();
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<StoredResponse>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(5)) {
            for (int i = 0; i < 5; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return executor.execute(account, "concurrent", HASH_A, () -> {
                        sleep(Duration.ofMillis(200));
                        return new StoredResponse(201, "{\"run\":" + runs.incrementAndGet() + "}");
                    });
                }));
            }
            start.countDown();
            for (Future<StoredResponse> result : results) {
                assertThat(result.get()).isEqualTo(new StoredResponse(201, "{\"run\":1}"));
            }
        }
        assertThat(runs).hasValue(1);
    }

    @Test
    void inFlightDuplicateGivesUpAfterWaitWindow() throws Exception {
        UUID account = UUID.randomUUID();
        JdbcIdempotentExecutor impatient = new JdbcIdempotentExecutor(jdbc, Clock.systemUTC(),
                Duration.ofMillis(100), Duration.ofSeconds(30));
        CountDownLatch running = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<StoredResponse> slow = pool.submit(() -> impatient.execute(account, "slow", HASH_A, () -> {
                running.countDown();
                sleep(Duration.ofMillis(800));
                return new StoredResponse(201, "{}");
            }));
            running.await();

            assertThatThrownBy(() -> impatient.execute(account, "slow", HASH_A, () -> new StoredResponse(201, "{}")))
                    .isInstanceOf(IdempotencyConflictException.InProgress.class);
            assertThat(slow.get().status()).isEqualTo(201);
        }
    }

    @Test
    void abandonedClaimIsTakenOver() {
        UUID account = UUID.randomUUID();
        Instant longAgo = Instant.now().minus(Duration.ofMinutes(5));
        jdbc.sql("INSERT INTO idempotency_keys VALUES (:a, 'crashed', :h, NULL, NULL, :t, :t)")
                .param("a", account).param("h", HASH_A).param("t", Timestamp.from(longAgo)).update();

        StoredResponse response = executor.execute(account, "crashed", HASH_A, () -> new StoredResponse(201, "{\"r\":1}"));

        assertThat(response.status()).isEqualTo(201);
    }

    @Test
    void expiredKeysAreTreatedAsNewAndPurged() {
        UUID account = UUID.randomUUID();
        Instant expired = Instant.now().minus(Duration.ofHours(25));
        jdbc.sql("INSERT INTO idempotency_keys VALUES (:a, 'old', :h, 201, '{\"old\":true}', :t, :t)")
                .param("a", account).param("h", HASH_B).param("t", Timestamp.from(expired)).update();

        assertThat(executor.execute(account, "old", HASH_A, () -> new StoredResponse(201, "{\"new\":true}")).body())
                .isEqualTo("{\"new\":true}");

        jdbc.sql("INSERT INTO idempotency_keys VALUES (:a, 'old2', :h, 201, '{}', :t, :t)")
                .param("a", account).param("h", HASH_B).param("t", Timestamp.from(expired)).update();
        assertThat(executor.purgeExpired()).isGreaterThanOrEqualTo(1);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
