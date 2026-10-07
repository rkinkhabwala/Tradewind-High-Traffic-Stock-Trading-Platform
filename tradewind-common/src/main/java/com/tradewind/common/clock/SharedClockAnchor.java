package com.tradewind.common.clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Makes every service agree on FAST_CLOCK time without hand-synchronised env vars. If {@code realEpoch} is
 * configured it is used as is. Otherwise the first service to start stores "realEpoch|simStart|speed" in a shared
 * store (Redis) with set-if-absent, and every service, including restarts, uses the stored anchor.
 * Deleting the stored anchor and restarting resets simulated time.
 */
public final class SharedClockAnchor {

    private static final Logger log = LoggerFactory.getLogger(SharedClockAnchor.class);

    /** A tiny key-value store; implemented over Redis by each service. Calls must have a timeout. */
    public interface AnchorStore {
        Optional<String> get();

        boolean putIfAbsent(String value);
    }

    public record Settings(MarketClocks.Mode mode, long speed, Instant simStart, Instant realEpoch) {
    }

    private SharedClockAnchor() {
    }

    public static MarketClock resolve(Settings settings, AnchorStore store, Clock real) {
        if (settings.mode() == MarketClocks.Mode.WALL) {
            return new WallMarketClock(real);
        }
        if (settings.realEpoch() != null) {
            return new FastMarketClock(real, settings.realEpoch(), settings.simStart(), settings.speed());
        }
        String proposed = real.instant() + "|" + settings.simStart() + "|" + settings.speed();
        if (store.putIfAbsent(proposed)) {
            log.info("Started shared market clock anchor {}", proposed);
        }
        String anchor = store.get().orElse(proposed);
        String[] parts = anchor.split("\\|");
        Instant realEpoch = Instant.parse(parts[0]);
        Instant simStart = Instant.parse(parts[1]);
        long speed = Long.parseLong(parts[2]);
        if (speed != settings.speed() || !simStart.equals(settings.simStart())) {
            log.warn("Using shared clock anchor {} which differs from local settings; delete it to reset", anchor);
        }
        return new FastMarketClock(real, realEpoch, simStart, speed);
    }
}
