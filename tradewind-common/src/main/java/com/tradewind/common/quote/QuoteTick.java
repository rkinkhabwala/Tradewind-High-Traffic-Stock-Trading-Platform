package com.tradewind.common.quote;

import com.tradewind.common.money.Price;

import java.time.Instant;
import java.util.Objects;

/**
 * One simulated quote update; payload of {@code QuoteTick} v1 events on {@code quotes.ticks} (keyed by symbol).
 * Sizes are whole shares displayed at the bid/ask; market orders larger than that fill partially.
 * {@code marketTime} is simulated market time (FAST_CLOCK aware), not wall-clock time.
 */
public record QuoteTick(String symbol, Price bid, Price ask, Price last, long bidSize, long askSize,
                        long volume, Instant marketTime) {

    public static final int VERSION = 1;

    public QuoteTick {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(bid, "bid");
        Objects.requireNonNull(ask, "ask");
        Objects.requireNonNull(last, "last");
        Objects.requireNonNull(marketTime, "marketTime");
        if (bid.compareTo(ask) >= 0) {
            throw new IllegalArgumentException("bid must be below ask: " + bid + " / " + ask);
        }
        if (bidSize <= 0 || askSize <= 0 || volume < 0) {
            throw new IllegalArgumentException("sizes must be positive");
        }
    }
}
