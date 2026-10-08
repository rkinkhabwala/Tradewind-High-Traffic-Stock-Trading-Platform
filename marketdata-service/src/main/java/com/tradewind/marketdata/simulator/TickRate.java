package com.tradewind.marketdata.simulator;

import java.time.Duration;

/**
 * A temporary speed-up of every symbol's tick rate, for load tests (spec 8: 10,000 ticks/s for a 60 s burst).
 * Set through the internal API; reverts to 1x on its own when the duration ends, so a forgotten burst cannot stick.
 */
public final class TickRate {

    public static final double MAX_MULTIPLIER = 10;
    public static final Duration MAX_DURATION = Duration.ofMinutes(10);

    public record Boost(double multiplier, long untilNanos) {
    }

    private volatile Boost boost = new Boost(1, 0);

    public void boost(double multiplier, Duration duration, long nowNanos) {
        if (multiplier < 1 || multiplier > MAX_MULTIPLIER || duration.isNegative() || duration.compareTo(MAX_DURATION) > 0) {
            throw new IllegalArgumentException("multiplier must be 1-" + (int) MAX_MULTIPLIER + " and duration 0-"
                    + MAX_DURATION.toMinutes() + " minutes");
        }
        boost = new Boost(multiplier, nowNanos + duration.toNanos());
    }

    /** The multiplier in effect at {@code nowNanos} (1 when no boost is active). */
    public double multiplier(long nowNanos) {
        Boost b = boost;
        return nowNanos < b.untilNanos() ? b.multiplier() : 1;
    }

    /** Remaining boost time at {@code nowNanos}, zero if none. */
    public Duration remaining(long nowNanos) {
        return Duration.ofNanos(Math.max(0, boost.untilNanos() - nowNanos));
    }
}
