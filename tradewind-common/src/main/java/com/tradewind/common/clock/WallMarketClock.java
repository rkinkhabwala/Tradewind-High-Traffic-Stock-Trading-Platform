package com.tradewind.common.clock;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/** Market time equals real time. */
public final class WallMarketClock implements MarketClock {

    private final Clock clock;

    public WallMarketClock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Instant now() {
        return clock.instant();
    }
}
