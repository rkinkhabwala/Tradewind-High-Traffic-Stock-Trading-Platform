package com.tradewind.portfolio.ledger;

import com.tradewind.portfolio.ledger.LedgerExceptions.LedgerContentionException;
import com.tradewind.portfolio.ledger.LedgerExceptions.OptimisticConflictException;
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

    private final TransactionTemplate transactions;
    private final int maxAttempts;

    LedgerTransactions(TransactionTemplate transactions, @Value("${tradewind.ledger.max-attempts:20}") int maxAttempts) {
        this.transactions = transactions;
        this.maxAttempts = maxAttempts;
    }

    public <T> T execute(Supplier<T> work) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return transactions.execute(status -> work.get());
            } catch (OptimisticConflictException | ConcurrencyFailureException | DuplicateKeyException e) {
                // ConcurrencyFailureException covers deadlocks Postgres resolves by aborting one transaction.
                backoff(attempt);
            }
        }
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
