package com.tradewind.common.idempotency;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Runs an operation at most once per {@code (account, Idempotency-Key)} and replays its recorded response
 * (spec section 4). Expects this table in the service's database:
 * <pre>
 * idempotency_keys(account_id UUID, key VARCHAR(128), request_hash CHAR(64), status_code INT NULL,
 *                  response_json TEXT NULL, created_at TIMESTAMPTZ, claimed_at TIMESTAMPTZ,
 *                  PRIMARY KEY (account_id, key))
 * </pre>
 * Each statement auto-commits so concurrent requests see the claim immediately; never call this inside a
 * transaction. The operation must itself be idempotent downstream, because a claim abandoned by a crashed
 * instance is taken over and the operation re-run.
 * <ul>
 *   <li>Operation returns: response recorded and returned (including 4xx domain outcomes).</li>
 *   <li>Operation throws: claim deleted so the client can retry; exception propagates.</li>
 *   <li>Concurrent duplicate: waits up to {@code waitForInFlight} for the original, then replays it or throws
 *       {@link IdempotencyConflictException.InProgress}.</li>
 * </ul>
 */
public class JdbcIdempotentExecutor {

    public static final Duration DEFAULT_WAIT_FOR_IN_FLIGHT = Duration.ofSeconds(2);
    public static final Duration DEFAULT_ABANDONED_AFTER = Duration.ofSeconds(30);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(25);

    private final JdbcClient jdbc;
    private final Clock clock;
    private final Duration waitForInFlight;
    private final Duration abandonedAfter;

    public JdbcIdempotentExecutor(JdbcClient jdbc, Clock clock) {
        this(jdbc, clock, DEFAULT_WAIT_FOR_IN_FLIGHT, DEFAULT_ABANDONED_AFTER);
    }

    public JdbcIdempotentExecutor(JdbcClient jdbc, Clock clock, Duration waitForInFlight, Duration abandonedAfter) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.waitForInFlight = waitForInFlight;
        this.abandonedAfter = abandonedAfter;
    }

    private record Row(String requestHash, Integer status, String body, Instant createdAt, Instant claimedAt) {
    }

    public StoredResponse execute(UUID accountId, String key, String requestHash, Supplier<StoredResponse> operation) {
        long deadline = System.nanoTime() + waitForInFlight.toNanos();
        while (true) {
            Instant now = clock.instant();
            if (tryClaim(accountId, key, requestHash, now)) {
                return runClaimed(accountId, key, operation);
            }
            Optional<Row> existing = find(accountId, key);
            if (existing.isEmpty()) {
                continue; // released or expired between our insert and select: try to claim again
            }
            Row row = existing.get();
            if (row.createdAt().isBefore(now.minus(IdempotencyKeys.RETENTION))) {
                deleteExpired(accountId, key, row.createdAt());
                continue;
            }
            if (!row.requestHash().equals(requestHash)) {
                throw new IdempotencyConflictException.KeyReused();
            }
            if (row.status() != null) {
                return new StoredResponse(row.status(), row.body());
            }
            if (row.claimedAt().isBefore(now.minus(abandonedAfter)) && takeOver(accountId, key, row.claimedAt(), now)) {
                return runClaimed(accountId, key, operation);
            }
            if (System.nanoTime() > deadline) {
                throw new IdempotencyConflictException.InProgress();
            }
            sleep();
        }
    }

    /** Deletes keys past the 24h retention. Services call this on a schedule. */
    public int purgeExpired() {
        return jdbc.sql("DELETE FROM idempotency_keys WHERE created_at < :cutoff")
                .param("cutoff", Timestamp.from(clock.instant().minus(IdempotencyKeys.RETENTION)))
                .update();
    }

    private StoredResponse runClaimed(UUID accountId, String key, Supplier<StoredResponse> operation) {
        StoredResponse response;
        try {
            response = operation.get();
        } catch (RuntimeException e) {
            jdbc.sql("DELETE FROM idempotency_keys WHERE account_id = :account AND key = :key AND status_code IS NULL")
                    .param("account", accountId).param("key", key).update();
            throw e;
        }
        jdbc.sql("""
                        UPDATE idempotency_keys SET status_code = :status, response_json = :body
                        WHERE account_id = :account AND key = :key AND status_code IS NULL""")
                .param("status", response.status()).param("body", response.body())
                .param("account", accountId).param("key", key)
                .update();
        return response;
    }

    private boolean tryClaim(UUID accountId, String key, String requestHash, Instant now) {
        return jdbc.sql("""
                        INSERT INTO idempotency_keys (account_id, key, request_hash, created_at, claimed_at)
                        VALUES (:account, :key, :hash, :now, :now)
                        ON CONFLICT (account_id, key) DO NOTHING""")
                .param("account", accountId).param("key", key).param("hash", requestHash)
                .param("now", Timestamp.from(now))
                .update() == 1;
    }

    private boolean takeOver(UUID accountId, String key, Instant previousClaim, Instant now) {
        return jdbc.sql("""
                        UPDATE idempotency_keys SET claimed_at = :now
                        WHERE account_id = :account AND key = :key AND status_code IS NULL AND claimed_at = :previous""")
                .param("now", Timestamp.from(now)).param("previous", Timestamp.from(previousClaim))
                .param("account", accountId).param("key", key)
                .update() == 1;
    }

    private Optional<Row> find(UUID accountId, String key) {
        return jdbc.sql("""
                        SELECT request_hash, status_code, response_json, created_at, claimed_at
                        FROM idempotency_keys WHERE account_id = :account AND key = :key""")
                .param("account", accountId).param("key", key)
                .query((rs, n) -> new Row(
                        rs.getString("request_hash"),
                        (Integer) rs.getObject("status_code"),
                        rs.getString("response_json"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("claimed_at").toInstant()))
                .optional();
    }

    private void deleteExpired(UUID accountId, String key, Instant createdAt) {
        jdbc.sql("DELETE FROM idempotency_keys WHERE account_id = :account AND key = :key AND created_at = :created")
                .param("account", accountId).param("key", key).param("created", Timestamp.from(createdAt))
                .update();
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL_INTERVAL);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IdempotencyConflictException.InProgress();
        }
    }
}
