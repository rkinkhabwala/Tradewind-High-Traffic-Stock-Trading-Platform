package com.tradewind.account.funding;

import java.util.UUID;

/**
 * portfolio-service owns the money. Both calls are idempotent per {@code referenceId}, so a timed-out call can be
 * retried safely.
 */
public interface PortfolioLedgerClient {

    record LedgerResult(UUID referenceId, long cashBalanceCents) {
    }

    LedgerResult deposit(UUID referenceId, UUID accountId, long amountCents);

    /** Throws {@link InsufficientFundsException} if available cash is too low. */
    LedgerResult withdraw(UUID referenceId, UUID accountId, long amountCents);

    class InsufficientFundsException extends RuntimeException {
        public InsufficientFundsException() {
            super("Insufficient available cash");
        }
    }

    /** Timeout, connection failure or 5xx: the outcome is unknown and the client may retry. */
    class PortfolioUnavailableException extends RuntimeException {
        public PortfolioUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
