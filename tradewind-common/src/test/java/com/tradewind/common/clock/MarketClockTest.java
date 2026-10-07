package com.tradewind.common.clock;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarketClockTest {

    // 2026-01-05 is a Monday (EST, UTC-5); 2026-03-09 is the Monday after DST starts (EDT, UTC-4).
    private static ZonedDateTime ny(String localDateTime) {
        return java.time.LocalDateTime.parse(localDateTime).atZone(MarketHours.ZONE);
    }

    @Test
    void sessionBoundariesAreInclusiveOpenExclusiveClose() {
        assertThat(MarketHours.isOpen(ny("2026-01-05T09:29:59"))).isFalse();
        assertThat(MarketHours.isOpen(ny("2026-01-05T09:30:00"))).isTrue();
        assertThat(MarketHours.isOpen(ny("2026-01-05T15:59:59"))).isTrue();
        assertThat(MarketHours.isOpen(ny("2026-01-05T16:00:00"))).isFalse();
    }

    @Test
    void weekendsAreClosed() {
        assertThat(MarketHours.isOpen(ny("2026-01-10T11:00:00"))).isFalse();
        assertThat(MarketHours.isOpen(ny("2026-01-11T11:00:00"))).isFalse();
    }

    @Test
    void evaluatesInNewYorkTimeAcrossDst() {
        assertThat(MarketHours.isOpen(ZonedDateTime.parse("2026-01-05T14:30:00Z"))).isTrue();
        assertThat(MarketHours.isOpen(ZonedDateTime.parse("2026-01-05T14:29:00Z"))).isFalse();
        assertThat(MarketHours.isOpen(ZonedDateTime.parse("2026-03-09T13:30:00Z"))).isTrue();
        assertThat(MarketHours.isOpen(ZonedDateTime.parse("2026-03-09T13:29:00Z"))).isFalse();
    }

    @Test
    void closeOfIsFourPmNewYork() {
        assertThat(MarketHours.closeOf(LocalDate.parse("2026-01-05")).toInstant())
                .isEqualTo(Instant.parse("2026-01-05T21:00:00Z"));
    }

    @Test
    void fastClockAdvancesAtConfiguredSpeed() {
        Instant realEpoch = Instant.parse("2026-10-07T12:00:00Z");
        Instant simEpoch = Instant.parse("2026-01-05T14:25:00Z"); // 09:25 New York
        Clock tenSecondsLater = Clock.fixed(realEpoch.plusSeconds(10), ZoneOffset.UTC);

        MarketClock clock = new FastMarketClock(tenSecondsLater, realEpoch, simEpoch, 60);

        assertThat(clock.now()).isEqualTo(Instant.parse("2026-01-05T14:35:00Z"));
        assertThat(clock.isMarketOpen()).isTrue();
        assertThat(clock.sessionDate()).isEqualTo(LocalDate.parse("2026-01-05"));
    }

    @Test
    void fastClockRejectsNonPositiveSpeed() {
        assertThatThrownBy(() -> new FastMarketClock(Clock.systemUTC(), Instant.EPOCH, Instant.EPOCH, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void factoryBuildsConfiguredMode() {
        Clock real = Clock.fixed(Instant.parse("2026-10-07T12:00:00Z"), ZoneOffset.UTC);

        assertThat(MarketClocks.create(MarketClocks.Mode.WALL, real, null, null, 1).now())
                .isEqualTo(real.instant());
        assertThat(MarketClocks.create(MarketClocks.Mode.FAST, real,
                "2026-10-07T12:00:00Z", "2026-01-05T09:25:00-05:00", 60).now())
                .isEqualTo(Instant.parse("2026-01-05T14:25:00Z"));
    }
}
