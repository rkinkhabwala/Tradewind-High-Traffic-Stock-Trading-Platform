package com.tradewind.common.idempotency;

/** Base for the two ways a request can collide with an existing idempotency key. */
public abstract sealed class IdempotencyConflictException extends RuntimeException
        permits IdempotencyConflictException.KeyReused, IdempotencyConflictException.InProgress {

    protected IdempotencyConflictException(String message) {
        super(message);
    }

    /** Same key, different request body: a client bug, answered with 422. */
    public static final class KeyReused extends IdempotencyConflictException {
        public KeyReused() {
            super(IdempotencyKeys.HEADER + " was already used for a different request");
        }
    }

    /** The original request is still running: answered with 409 and Retry-After. */
    public static final class InProgress extends IdempotencyConflictException {
        public InProgress() {
            super("A request with this " + IdempotencyKeys.HEADER + " is still being processed");
        }
    }
}
