package com.tradewind.common.idempotency;

public class InvalidIdempotencyKeyException extends IllegalArgumentException {

    public InvalidIdempotencyKeyException(String message) {
        super(message);
    }
}
