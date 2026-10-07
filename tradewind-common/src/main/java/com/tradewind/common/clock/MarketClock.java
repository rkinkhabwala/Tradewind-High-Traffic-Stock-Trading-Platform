package com.tradewind.common.clock;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;

/**
 * Simulated market time, shared by every service that checks market hours or session boundaries.
 * All services configured with the same settings compute the same time, so no clock service is needed.
 */
public interface MarketClock {

    Instant now();

    default ZonedDateTime marketTime() {
        return now().atZone(MarketHours.ZONE);
    }

    default boolean isMarketOpen() {
        return MarketHours.isOpen(marketTime());
    }

    default LocalDate sessionDate() {
        return marketTime().toLocalDate();
    }
}
