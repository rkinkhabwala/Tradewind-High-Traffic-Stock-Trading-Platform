package com.tradewind.common.clock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * FAST_CLOCK mode: {@code simulated = simEpoch + (real - realEpoch) x speed}. Deterministic, so every service
 * configured with the same epochs and speed agrees on market time.
 */
public final class FastMarketClock implements MarketClock {

    private final Clock real;
    private final Instant realEpoch;
    private final Instant simEpoch;
    private final long speed;

    public FastMarketClock(Clock real, Instant realEpoch, Instant simEpoch, long speed) {
        if (speed < 1) {
            throw new IllegalArgumentException("speed must be >= 1: " + speed);
        }
        this.real = Objects.requireNonNull(real, "real");
        this.realEpoch = Objects.requireNonNull(realEpoch, "realEpoch");
        this.simEpoch = Objects.requireNonNull(simEpoch, "simEpoch");
        this.speed = speed;
    }

    @Override
    public Instant now() {
        Duration elapsed = Duration.between(realEpoch, real.instant());
        return simEpoch.plus(elapsed.multipliedBy(speed));
    }
}
