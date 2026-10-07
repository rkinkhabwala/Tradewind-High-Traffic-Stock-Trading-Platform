package com.tradewind.account.funding;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory stand-in for portfolio-service's ledger, idempotent per reference like the real one. Lets tests
 * count calls and simulate an outage or a slow response.
 */
class FakePortfolioLedger implements PortfolioLedgerClient {

    final AtomicInteger calls = new AtomicInteger();
    private final Map<UUID, Long> cash = new ConcurrentHashMap<>();
    private final Map<UUID, LedgerResult> byReference = new ConcurrentHashMap<>();
    volatile boolean unavailable;
    volatile Duration delay = Duration.ZERO;

    @Override
    public LedgerResult deposit(UUID referenceId, UUID accountId, long amountCents) {
        return apply(referenceId, accountId, amountCents);
    }

    @Override
    public LedgerResult withdraw(UUID referenceId, UUID accountId, long amountCents) {
        return apply(referenceId, accountId, -amountCents);
    }

    private synchronized LedgerResult apply(UUID referenceId, UUID accountId, long delta) {
        calls.incrementAndGet();
        pause();
        if (unavailable) {
            throw new PortfolioUnavailableException("simulated outage", null);
        }
        LedgerResult previous = byReference.get(referenceId);
        if (previous != null) {
            return previous;
        }
        long balance = cash.getOrDefault(accountId, 0L) + delta;
        if (balance < 0) {
            throw new InsufficientFundsException();
        }
        cash.put(accountId, balance);
        LedgerResult result = new LedgerResult(referenceId, balance);
        byReference.put(referenceId, result);
        return result;
    }

    private void pause() {
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
