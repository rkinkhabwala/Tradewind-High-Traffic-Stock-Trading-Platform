package com.tradewind.portfolio.ledger;

import com.tradewind.portfolio.ledger.LedgerExceptions.LedgerContentionException;
import com.tradewind.portfolio.ledger.LedgerExceptions.OptimisticConflictException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Runs ledger work in a fresh transaction, retrying the whole unit when an optimistic-lock conflict (or a
 * concurrent first-time account creation) makes it roll back. Work must be safe to re-run, which it is because
 * every posting is keyed by a unique reference.
 */
@Component
public class LedgerTransactions {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LedgerTransactions.class);

    private final TransactionTemplate transactions;
    private final int maxAttempts;
    private final Counter conflicts;
    private final Counter gaveUp;

    LedgerTransactions(TransactionTemplate transactions, @Value("${tradewind.ledger.max-attempts:20}") int maxAttempts,
                       MeterRegistry meters) {
        this.transactions = transactions;
        this.maxAttempts = maxAttempts;
        this.conflicts = Counter.builder("ledger.conflicts")
                .description("Optimistic-lock conflicts retried (contended accounts)").register(meters);
        this.gaveUp = Counter.builder("ledger.gave_up")
                .description("Ledger transactions abandoned after max attempts (answered 503)").register(meters);
    }

    public <T> T execute(Supplier<T> work) {
        long started = System.nanoTime();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                T result = transactions.execute(status -> work.get());
                if (attempt > 3) {
                    log.info("Ledger transaction needed {} attempts ({} ms)", attempt,
                            (System.nanoTime() - started) / 1_000_000);
                }
                return result;
            } catch (OptimisticConflictException | ConcurrencyFailureException | DuplicateKeyException e) {
                // ConcurrencyFailureException covers deadlocks Postgres resolves by aborting one transaction.
                conflicts.increment();
                backoff(attempt);
            }
        }
        log.warn("Ledger transaction gave up after {} optimistic-lock conflicts", maxAttempts);
        gaveUp.increment();
        throw new LedgerContentionException();
    }

    private static void backoff(int attempt) {
        long maxMillis = Math.min(100, 2L << attempt);
        try {
            Thread.sleep(Duration.ofMillis(ThreadLocalRandom.current().nextLong(1, maxMillis + 1)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LedgerContentionException();
        }
    }
}
