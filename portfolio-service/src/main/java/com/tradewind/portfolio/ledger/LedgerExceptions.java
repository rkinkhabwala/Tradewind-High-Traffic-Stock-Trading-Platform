package com.tradewind.portfolio.ledger;

public final class LedgerExceptions {

    private LedgerExceptions() {
    }

    /** The posting would make a non-house balance negative. Nothing was written. */
    public static class InsufficientBalanceException extends RuntimeException {
        private final LedgerAccountKey account;

        public InsufficientBalanceException(LedgerAccountKey account) {
            super("Insufficient " + account.type() + " balance in " + account.unit());
            this.account = account;
        }

        public LedgerAccountKey account() {
            return account;
        }
    }

    /** A balance row changed between read and write; the whole transaction is retried. */
    public static class OptimisticConflictException extends RuntimeException {
        public OptimisticConflictException() {
            super("Balance changed concurrently");
        }
    }

    /** Retries exhausted under heavy contention. Safe for the caller to retry with the same reference. */
    public static class LedgerContentionException extends RuntimeException {
        public LedgerContentionException() {
            super("Ledger is busy, retry");
        }
    }

    /** A reference id was reused for a different business event. */
    public static class ConflictingReferenceException extends RuntimeException {
        public ConflictingReferenceException(String message) {
            super(message);
        }
    }
}
