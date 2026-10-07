package com.tradewind.common.clock;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SharedClockAnchorTest {

    static class MemoryStore implements SharedClockAnchor.AnchorStore {
        final AtomicReference<String> value = new AtomicReference<>();

        @Override
        public Optional<String> get() {
            return Optional.ofNullable(value.get());
        }

        @Override
        public boolean putIfAbsent(String v) {
            return value.compareAndSet(null, v);
        }
    }

    private static final Instant SIM_START = Instant.parse("2026-01-05T14:29:00Z"); // Monday 09:29 New York

    @Test
    void servicesStartingAtDifferentTimesShareTheFirstAnchor() {
        MemoryStore store = new MemoryStore();
        var settings = new SharedClockAnchor.Settings(MarketClocks.Mode.FAST, 60, SIM_START, null);
        Instant firstStart = Instant.parse("2026-10-07T12:00:00Z");

        MarketClock first = SharedClockAnchor.resolve(settings, store, Clock.fixed(firstStart, ZoneOffset.UTC));
        MarketClock second = SharedClockAnchor.resolve(settings, store,
                Clock.fixed(firstStart.plusSeconds(30), ZoneOffset.UTC));

        assertThat(first.now()).isEqualTo(SIM_START);
        assertThat(second.now()).isEqualTo(SIM_START.plusSeconds(30 * 60));
        assertThat(store.value.get()).startsWith("2026-10-07T12:00:00Z|");
    }

    @Test
    void explicitRealEpochBypassesTheStore() {
        MemoryStore store = new MemoryStore();
        Instant epoch = Instant.parse("2026-10-07T12:00:00Z");
        var settings = new SharedClockAnchor.Settings(MarketClocks.Mode.FAST, 1, SIM_START, epoch);

        MarketClock clock = SharedClockAnchor.resolve(settings, store, Clock.fixed(epoch.plusSeconds(90), ZoneOffset.UTC));

        assertThat(clock.now()).isEqualTo(SIM_START.plusSeconds(90));
        assertThat(clock.isMarketOpen()).isTrue();
        assertThat(store.value.get()).isNull();
    }

    @Test
    void wallModeIsRealTime() {
        Clock real = Clock.fixed(Instant.parse("2026-10-07T12:00:00Z"), ZoneOffset.UTC);
        var settings = new SharedClockAnchor.Settings(MarketClocks.Mode.WALL, 1, SIM_START, null);

        assertThat(SharedClockAnchor.resolve(settings, new MemoryStore(), real).now()).isEqualTo(real.instant());
    }
}
