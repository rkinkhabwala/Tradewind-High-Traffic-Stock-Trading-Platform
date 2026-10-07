package com.tradewind.order.portfolio;

import com.tradewind.common.money.Quantity;

import java.util.UUID;

/**
 * portfolio-service's reservation API (spec 6.3 step 3). Every call is idempotent per order id, so a call whose
 * outcome is unknown (timeout, circuit open) can simply be repeated.
 */
public interface ReservationClient {

    sealed interface Outcome permits Reserved, Insufficient, AlreadyReleased {
    }

    record Reserved() implements Outcome {
    }

    /** {@code code} is INSUFFICIENT_BUYING_POWER or INSUFFICIENT_SHARES. */
    record Insufficient(String code) implements Outcome {
    }

    /** The order was released (by the PENDING sweeper) before this reserve arrived; nothing is held. */
    record AlreadyReleased() implements Outcome {
    }

    Outcome reserveCash(UUID orderId, UUID accountId, long cents);

    Outcome reserveShares(UUID orderId, UUID accountId, String symbol, Quantity quantity);

    void release(UUID orderId);

    /** Timeout, 5xx, circuit open or bulkhead full: the outcome is unknown and the caller may retry. */
    class ReservationUnavailableException extends RuntimeException {
        public ReservationUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
