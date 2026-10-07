package com.tradewind.common.clock;

import java.time.Clock;
import java.time.OffsetDateTime;

/** Builds the configured {@link MarketClock}. Services pass values from {@code tradewind.clock.*} properties. */
public final class MarketClocks {

    public enum Mode { WALL, FAST }

    private MarketClocks() {
    }

    /**
     * @param realEpoch ISO-8601 offset date-time; ignored in WALL mode
     * @param simEpoch  ISO-8601 offset date-time; ignored in WALL mode
     */
    public static MarketClock create(Mode mode, Clock real, String realEpoch, String simEpoch, long speed) {
        return switch (mode) {
            case WALL -> new WallMarketClock(real);
            case FAST -> new FastMarketClock(real,
                    OffsetDateTime.parse(realEpoch).toInstant(),
                    OffsetDateTime.parse(simEpoch).toInstant(),
                    speed);
        };
    }
}
